package com.github.adamantcheese.chan.core.manager;

import android.annotation.SuppressLint;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.adamantcheese.chan.core.database.DatabaseManager;
import com.github.adamantcheese.chan.core.database.DatabaseSavedThreadManager;
import com.github.adamantcheese.chan.core.model.Post;
import com.github.adamantcheese.chan.core.model.PostImage;
import com.github.adamantcheese.chan.core.model.orm.Loadable;
import com.github.adamantcheese.chan.core.model.orm.Pin;
import com.github.adamantcheese.chan.core.model.orm.PinType;
import com.github.adamantcheese.chan.core.model.save.SerializableThread;
import com.github.adamantcheese.chan.core.repository.SavedThreadLoaderRepository;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.ui.settings.base_directory.LocalThreadsBaseDirectory;
import com.github.adamantcheese.chan.utils.BackgroundUtils;
import com.github.adamantcheese.chan.utils.IOUtils;
import com.github.adamantcheese.chan.utils.Logger;
import com.github.adamantcheese.chan.utils.StringUtils;
import com.github.k1rakishou.fsaf.FileManager;
import com.github.k1rakishou.fsaf.file.AbstractFile;
import com.github.k1rakishou.fsaf.file.DirectorySegment;
import com.github.k1rakishou.fsaf.file.FileSegment;
import com.github.k1rakishou.fsaf.file.Segment;
import com.github.k1rakishou.fsaf.manager.BaseFileManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.processors.PublishProcessor;
import io.reactivex.schedulers.Schedulers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

public class ThreadSaveManager {
    private static final int REQUEST_BUFFERING_TIME_SECONDS = 30;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public static final String SAVED_THREADS_DIR_NAME = "saved_threads";
    public static final String IMAGES_DIR_NAME = "images";
    public static final String SPOILER_FILE_NAME = "spoiler";
    public static final String THUMBNAIL_FILE_NAME = "thumbnail";
    public static final String ORIGINAL_FILE_NAME = "original";
    public static final String NO_MEDIA_FILE_NAME = ".nomedia";

    private final DatabaseManager databaseManager;
    private final DatabaseSavedThreadManager databaseSavedThreadManager;
    private final SavedThreadLoaderRepository savedThreadLoaderRepository;
    private final FileManager fileManager;
    private final boolean verboseLogsEnabled;

    @GuardedBy("itself")
    private final Map<Loadable, SaveThreadParameters> activeDownloads = new HashMap<>();
    @GuardedBy("activeDownloads")
    private final Map<Loadable, AdditionalThreadParameters> additionalThreadParameter = new HashMap<>();

    private OkHttpClient okHttpClient;
    private ExecutorService executorService = Executors.newFixedThreadPool(getThreadsCountForDownloaderExecutor());
    private PublishProcessor<Loadable> workerQueue = PublishProcessor.create();
    private AtomicBoolean cancelingRunning = new AtomicBoolean(false);

    private static int getThreadsCountForDownloaderExecutor() {
        int threadsCount = (Runtime.getRuntime().availableProcessors() / 2) + 1;
        if (threadsCount < 3) {
            // We need at least two worker threads and one thread for the rx stream itself. More threads
            // will make the phone laggy, less threads will make downloading really slow.
            threadsCount = 3;
        }
        return threadsCount;
    }

    @Inject
    public ThreadSaveManager(
            DatabaseManager databaseManager,
            OkHttpClient okHttpClient,
            SavedThreadLoaderRepository savedThreadLoaderRepository,
            FileManager fileManager
    ) {
        this.okHttpClient = okHttpClient;
        this.databaseManager = databaseManager;
        this.savedThreadLoaderRepository = savedThreadLoaderRepository;
        this.databaseSavedThreadManager = databaseManager.getDatabaseSavedThreadManager();
        this.fileManager = fileManager;
        this.verboseLogsEnabled = ChanSettings.verboseLogs.get();

        initRxWorkerQueue();
    }

    /**
     * Initializes main rx queue that is going to accept new downloading requests and process them
     * sequentially.
     * <p>
     * This class is a singleton so we don't really care about disposing of the rx stream
     */
    @SuppressLint("CheckResult")
    private void initRxWorkerQueue() {
        // Just buffer everything in the internal queue when the consumers are slow (and they are
        // always slow because they have to download images, but we check whether a download request
        // is already enqueued so it's okay for us to rely on the buffering)
        workerQueue
                // Collect all the request over some time
                .buffer(REQUEST_BUFFERING_TIME_SECONDS, SECONDS)
                .onBackpressureBuffer()
                .filter((requests) -> !requests.isEmpty())
                .concatMap(this::processCollectedRequests)
                .subscribe(res -> { },
                        // OK
                        error -> {
                            throw new RuntimeException(
                                    ThreadSaveManager.class.getSimpleName() + " Uncaught exception!!! "
                                            + "workerQueue is in error state now!!! "
                                            + "This should not happen!!!, original error = " + error.getMessage());
                        }, () -> {
                            throw new RuntimeException(ThreadSaveManager.class.getSimpleName()
                                    + " workerQueue stream has completed!!! This should not happen!!!");
                        }
                );
    }

    private Flowable<Boolean> processCollectedRequests(List<Loadable> loadableList) {
        if (loadableList.isEmpty()) {
            return Flowable.just(true);
        }

        Logger.d(this, "Collected " + loadableList.size() + " local thread download requests");

        AbstractFile baseLocalThreadsDirectory = fileManager.newBaseDirectoryFile(LocalThreadsBaseDirectory.class);
        if (baseLocalThreadsDirectory == null) {
            Logger.e(this, "LocalThreadsBaseDirectory is not registered!");
            return Flowable.just(false);
        }

        /*
         * Create an in-memory snapshot of a directory with files and sub directories with their
         * files. This will SIGNIFICANTLY improve the files operations speed until this snapshot is
         * released. For this reason we collect the request so that we can create a snapshot process
         * all of the collected request in one big batch and then release the snapshot.
         */
        Logger.d(this, "Snapshot created");
        BaseFileManager snapshotFileManager = fileManager.createSnapshot(baseLocalThreadsDirectory, true);

        return Flowable.fromIterable(loadableList).concatMap(loadable -> {
            SaveThreadParameters parameters;
            List<Post> postsToSave = new ArrayList<>();

            synchronized (activeDownloads) {
                Logger.d(ThreadSaveManager.this,
                        "New downloading request started " + loadable.toShortString() + ", activeDownloads count = "
                                + activeDownloads.size()
                );
                parameters = activeDownloads.get(loadable);

                if (parameters != null) {
                    // Use a copy of the list to avoid ConcurrentModificationExceptions
                    postsToSave.addAll(parameters.postsToSave);
                }
            }

            if (parameters == null) {
                Logger.e(ThreadSaveManager.this, "Could not find download parameters for loadable " + loadable.toShortString());
                return Flowable.just(false);
            }

            return saveThreadInternal(loadable, postsToSave, snapshotFileManager)
                    // Use the executor's thread to process the queue elements. Everything above
                    // will executed on this executor's threads.
                    .subscribeOn(Schedulers.from(executorService))
                    // Everything below will be executed on the main thread
                    .observeOn(AndroidSchedulers.mainThread())
                    // Handle errors
                    .doOnError(error -> onDownloadingError(error, loadable))
                    // Handle results
                    .doOnSuccess(result -> onDownloadingCompleted(result, loadable)).doOnEvent((result, error) -> {
                        synchronized (activeDownloads) {
                            Logger.d(ThreadSaveManager.this,
                                    "Downloading request has completed for loadable " + loadable.toShortString()
                                            + ", activeDownloads count = " + activeDownloads.size()
                            );
                        }
                    })
                    // Suppress all of the exceptions so that the stream does not complete
                    .onErrorReturnItem(false).toFlowable();
        });
    }

    /**
     * Enqueues a thread's posts with all the images/webm/etc to be saved to the disk.
     */
    public boolean enqueueThreadToSave(Loadable loadable, List<Post> postsToSave) {
        BackgroundUtils.ensureMainThread();

        if (!fileManager.baseDirectoryExists(LocalThreadsBaseDirectory.class)) {
            Logger.e(this, "Base local threads directory does not exist, can't start downloading");
            return false;
        }

        synchronized (activeDownloads) {
            // Check if a thread is already being downloaded
            if (activeDownloads.containsKey(loadable)) {
                if (verboseLogsEnabled) {
                    Logger.d(this, "Downloader is already running for " + loadable.toShortString());
                }

                return true;
            }
        }

        SaveThreadParameters parameters = new SaveThreadParameters(postsToSave);

        // Store the parameters of this download
        synchronized (activeDownloads) {
            activeDownloads.put(loadable, parameters);

            if (!additionalThreadParameter.containsKey(loadable)) {
                additionalThreadParameter.put(loadable, new AdditionalThreadParameters());
            }
        }

        Logger.d(this, "Enqueued new download request for loadable " + loadable.toShortString());

        // Enqueue the download
        workerQueue.onNext(loadable);
        return true;
    }

    public boolean isThereAtLeastOneActiveDownload() {
        boolean hasActiveDownloads;

        synchronized (activeDownloads) {
            hasActiveDownloads = !activeDownloads.isEmpty();
        }

        return hasActiveDownloads;
    }

    public int countActiveDownloads() {
        synchronized (activeDownloads) {
            return activeDownloads.size();
        }
    }

    /**
     * Cancels all downloads
     */
    public void cancelAllDownloading() {
        synchronized (activeDownloads) {
            if (activeDownloads.isEmpty()) {
                return;
            }

            if (cancelingRunning.compareAndSet(false, true)) {
                return;
            }

            for (Map.Entry<Loadable, SaveThreadParameters> entry : activeDownloads.entrySet()) {
                SaveThreadParameters parameters = entry.getValue();

                parameters.cancel();
            }

            additionalThreadParameter.clear();
        }

        Logger.d(this, "Canceling all active thread downloads");

        databaseManager.runTask(() -> {
            try {
                List<Pin> pins = databaseManager.getDatabasePinManager().getPins().call();
                if (pins.isEmpty()) {
                    return null;
                }

                List<Pin> downloadPins = new ArrayList<>();

                for (Pin pin : pins) {
                    if (PinType.hasDownloadFlag(pin.pinType)) {
                        downloadPins.add(pin);
                    }
                }

                if (downloadPins.isEmpty()) {
                    return null;
                }

                databaseManager.getDatabaseSavedThreadManager().deleteAllSavedThreads().call();

                for (Pin pin : downloadPins) {
                    pin.pinType = PinType.removeDownloadNewPostsFlag(pin.pinType);

                    if (PinType.hasWatchNewPostsFlag(pin.pinType)) {
                        continue;
                    }

                    // We don't want to delete all of the users's bookmarks so we just change their
                    // types to WatchNewPosts
                    pin.pinType = PinType.addWatchNewPostsFlag(pin.pinType);
                }

                databaseManager.getDatabasePinManager().updatePins(downloadPins).call();

                for (Pin pin : downloadPins) {
                    databaseManager.getDatabaseSavedThreadManager().deleteThreadFromDisk(pin.loadable);
                }

                return null;
            } finally {
                cancelingRunning.set(false);
            }
        });
    }

    /**
     * Cancels a download associated with this loadable. Cancelling means that the user has completely
     * removed the pin associated with it. This means that we need to delete thread's files from the disk
     * as well.
     */
    public void cancelDownloading(Loadable loadable) {
        synchronized (activeDownloads) {
            SaveThreadParameters parameters = activeDownloads.get(loadable);
            if (parameters == null) {
                Logger.w(this,
                        "cancelDownloading Could not find SaveThreadParameters for loadable " + loadable.toShortString()
                );
                return;
            }

            Logger.d(this, "Cancelling a download for loadable " + loadable.toShortString());
            additionalThreadParameter.remove(loadable);
            parameters.cancel();
        }
    }

    /**
     * Stops a download associated with this loadable. Stopping means that the user unpressed
     * "save thread" button. This does not mean that they do not want to save this thread anymore
     * they may press it again later. So we don't need to delete thread's files from the disk in this
     * case.
     */
    public void stopDownloading(Loadable loadable) {
        synchronized (activeDownloads) {
            SaveThreadParameters parameters = activeDownloads.get(loadable);
            if (parameters == null) {
                Logger.w(this,
                        "stopDownloading Could not find SaveThreadParameters for loadable " + loadable.toShortString()
                );
                return;
            }

            Logger.d(this, "Stopping a download for loadable " + loadable.toShortString());
            parameters.stop();
        }
    }

    private void onDownloadingCompleted(boolean result, Loadable loadable) {
        synchronized (activeDownloads) {
            SaveThreadParameters saveThreadParameters = activeDownloads.get(loadable);
            if (saveThreadParameters == null) {
                Logger.w(this,
                        "Attempt to remove non existing active download with loadable " + loadable.toShortString()
                );
                return;
            }

            Logger.d(this, "Download for loadable " + loadable.toShortString() + " ended up with result " + result);

            // Remove the download
            activeDownloads.remove(loadable);
        }
    }

    private void onDownloadingError(Throwable error, Loadable loadable) {
        synchronized (activeDownloads) {
            SaveThreadParameters saveThreadParameters = activeDownloads.get(loadable);
            if (saveThreadParameters == null) {
                Logger.w(this,
                        "Attempt to remove non existing active download with loadable " + loadable.toShortString()
                );
                return;
            }

            if (isFatalException(error)) {
                Logger.d(this, "Download for loadable " + loadable.toShortString() + " ended up with an error", error);
            }

            // Remove the download
            activeDownloads.remove(loadable);
        }
    }

    /**
     * Saves new posts to the disk asynchronously. Does duplicates checking internally so it is
     * safe to just pass all posts in there. Checks whether the current loadable is already
     * being downloaded. Checks for images that couldn't be downloaded on the previous attempt
     * (because of IO errors/bad network/server being down etc)
     *
     * @param loadable            is a unique identifier of a thread we are saving.
     * @param postsToSave         posts of a thread to be saved.
     * @param snapshotFileManager is either a RawFileManager (in case of local threads base directory
     *                            using Java File API) or SnapshotFileManager (basically an in-memory
     *                            snapshot of the whole directory).
     */
    private Single<Boolean> saveThreadInternal(
            @NonNull Loadable loadable, List<Post> postsToSave, BaseFileManager snapshotFileManager
    ) {
        return Single.fromCallable(() -> {
            BackgroundUtils.ensureBackgroundThread();

            if (!isCurrentDownloadRunning(loadable)) {
                // This download was canceled or stopped while waiting in the queue.
                Logger.d(ThreadSaveManager.this,
                        "Download for loadable " + loadable.toShortString()
                                + " was canceled or stopped while it was waiting in the queue"
                );
                return false;
            }

            Logger.d(ThreadSaveManager.this,
                    "Starting a new download for " + loadable.toShortString() + ", on thread "
                            + currentThread().getName()
            );

            AbstractFile threadSaveDir = getThreadSaveDir(loadable);
            if (!snapshotFileManager.exists(threadSaveDir) && snapshotFileManager.create(threadSaveDir) == null) {
                throw new CouldNotCreateThreadDirectoryException(threadSaveDir);
            }

            AbstractFile threadSaveDirImages = threadSaveDir.clone(new DirectorySegment(IMAGES_DIR_NAME));

            if (!snapshotFileManager.exists(threadSaveDirImages)
                    && snapshotFileManager.create(threadSaveDirImages) == null) {
                throw new CouldNotCreateImagesDirectoryException(threadSaveDirImages);
            }

            AbstractFile boardSaveDir = getBoardSaveDir(loadable);
            if (!snapshotFileManager.exists(boardSaveDir) && snapshotFileManager.create(boardSaveDir) == null) {
                throw new CouldNotCreateSpoilerImageDirectoryException(boardSaveDir);
            }

            dealWithMediaScanner(snapshotFileManager, threadSaveDirImages);

            // Filter out already saved posts and sort new posts in ascending order
            List<Post> newPosts = filterAndSortPosts(snapshotFileManager, threadSaveDirImages, loadable, postsToSave);

            if (newPosts.isEmpty()) {
                Logger.d(ThreadSaveManager.this, "No new posts for a thread " + loadable.toShortString());
                throw new NoNewPostsToSaveException();
            }

            int imagesTotalCount = calculateAmountOfImages(newPosts);
            int maxImageIoErrors = calculateMaxImageIoErrors(imagesTotalCount);

            Logger.d(ThreadSaveManager.this,
                    "" + newPosts.size() + " new posts for a thread " + loadable.no + ", images total "
                            + imagesTotalCount
            );

            // Get spoiler image url
            @Nullable
            final HttpUrl spoilerImageUrl = getSpoilerImageUrl(newPosts);

            // Try to load old serialized thread
            @Nullable
            SerializableThread serializableThread =
                    savedThreadLoaderRepository.loadOldThreadFromJsonFile(threadSaveDir);

            // Add new posts to the already saved posts (if there are any)
            savedThreadLoaderRepository.savePostsToJsonFile(serializableThread, newPosts, threadSaveDir);

            AtomicInteger currentImageDownloadIndex = new AtomicInteger(0);
            AtomicInteger imageDownloadsWithIoError = new AtomicInteger(0);

            try {
                downloadInternal(snapshotFileManager,
                        loadable,
                        threadSaveDirImages,
                        boardSaveDir,
                        newPosts,
                        imagesTotalCount,
                        maxImageIoErrors,
                        spoilerImageUrl,
                        currentImageDownloadIndex,
                        imageDownloadsWithIoError
                );
            } finally {
                if (shouldDeleteDownloadedFiles(loadable)) {
                    if (isCurrentDownloadStopped(loadable)) {
                        Logger.d(ThreadSaveManager.this, "Thread with loadable " + loadable.toShortString() + " has been stopped");
                    } else {
                        Logger.d(ThreadSaveManager.this, "Thread with loadable " + loadable.toShortString() + " has been canceled");
                    }

                    deleteThreadFilesFromDisk(snapshotFileManager, loadable);
                } else {
                    Logger.d(ThreadSaveManager.this, "Thread with loadable " + loadable.toShortString() + " has been updated");
                }
            }

            return true;
        });
    }

    @SuppressLint("CheckResult")
    private void downloadInternal(
            BaseFileManager snapshotFileManager,
            @NonNull Loadable loadable,
            AbstractFile threadSaveDirImages,
            AbstractFile boardSaveDir,
            List<Post> newPosts,
            int imagesTotalCount,
            int maxImageIoErrors,
            @Nullable HttpUrl spoilerImageUrl,
            AtomicInteger currentImageDownloadIndex,
            AtomicInteger imageDownloadsWithIoError
    ) {
        Single.fromCallable(() -> downloadSpoilerImage(snapshotFileManager, loadable, boardSaveDir, spoilerImageUrl))
                .flatMap(res -> {
                    // For each post create a new inner rx stream (so they can be processed in parallel)
                    return Flowable.fromIterable(newPosts)
                            // Here we create a separate reactive stream for each image request.
                            // But we use an executor service with limited threads amount, so there
                            // will be only this much at a time.
                            //                   |
                            //                 / | \
                            //                /  |  \
                            //               /   |   \
                            //               V   V   V // Separate streams.
                            //               |   |   |
                            //               o   o   o // Download images in parallel.
                            //               |   |   |
                            //               V   V   V // Combine them back to a single stream.
                            //               \   |   /
                            //                \  |  /
                            //                 \ | /
                            //                   |
                            .flatMap(post -> downloadImages(snapshotFileManager,
                                    loadable,
                                    threadSaveDirImages,
                                    post,
                                    currentImageDownloadIndex,
                                    imagesTotalCount,
                                    imageDownloadsWithIoError,
                                    maxImageIoErrors
                            )).toList().doOnSuccess(this::printBatchDownloadResults);
                })
                .flatMap(res -> Single.defer(() -> tryUpdateLastSavedPostNo(loadable, newPosts)))
                // Have to use blockingGet here. This is a place where all of the exception will come
                // out from
                .blockingGet();
    }

    private void printBatchDownloadResults(List<Boolean> results) {
        int successCount = 0;
        int failedCount = 0;

        for (Boolean result : results) {
            if (result) {
                ++successCount;
            } else {
                ++failedCount;
            }
        }

        Logger.d(this,
                "Thread downloaded, images downloaded: " + successCount + ", images failed to download: " + failedCount
        );
    }

    private Single<Boolean> tryUpdateLastSavedPostNo(@NonNull Loadable loadable, List<Post> newPosts) {
        if (!isCurrentDownloadRunning(loadable)) {
            if (isCurrentDownloadStopped(loadable)) {
                Logger.d(this, "Thread downloading has been stopped " + loadable.toShortString());
            } else {
                Logger.d(this, "Thread downloading has been canceled " + loadable.toShortString());
            }

            return Single.just(false);
        }

        updateLastSavedPostNo(loadable, newPosts);

        Logger.d(this, "Successfully updated a thread " + loadable.toShortString());
        return Single.just(true);
    }

    private AbstractFile getBoardSaveDir(Loadable loadable)
            throws IOException {
        if (!fileManager.baseDirectoryExists(LocalThreadsBaseDirectory.class)) {
            throw new IOException("getBoardSaveDir() Base local threads directory does not exist");
        }

        AbstractFile baseDir = fileManager.newBaseDirectoryFile(LocalThreadsBaseDirectory.class);
        if (baseDir == null) {
            throw new IOException("getBoardSaveDir() fileManager.newLocalThreadFile() returned null");
        }

        return baseDir.clone(getBoardSubDir(loadable));
    }

    private AbstractFile getThreadSaveDir(Loadable loadable)
            throws IOException {
        if (!fileManager.baseDirectoryExists(LocalThreadsBaseDirectory.class)) {
            throw new IOException("getThreadSaveDir() Base local threads directory does not exist");
        }

        AbstractFile baseDir = fileManager.newBaseDirectoryFile(LocalThreadsBaseDirectory.class);
        if (baseDir == null) {
            throw new IOException("getThreadSaveDir() fileManager.newLocalThreadFile() returned null");
        }

        return baseDir.clone(getThreadSubDir(loadable));
    }

    private void dealWithMediaScanner(BaseFileManager snapshotFileManager, AbstractFile threadSaveDirImages)
            throws CouldNotCreateNoMediaFile {
        AbstractFile noMediaFile = threadSaveDirImages.clone(new FileSegment(NO_MEDIA_FILE_NAME));

        if (!ChanSettings.allowMediaScannerToScanLocalThreads.get()) {
            // .nomedia file being in the images directory "should" prevent media scanner from
            // scanning this directory
            if (!snapshotFileManager.exists(noMediaFile) && snapshotFileManager.create(noMediaFile) == null) {
                throw new CouldNotCreateNoMediaFile(threadSaveDirImages);
            }
        } else {
            if (snapshotFileManager.exists(noMediaFile) && !snapshotFileManager.delete(noMediaFile)) {
                Logger.e(this, "Could not delete .nomedia file from directory " + threadSaveDirImages.getFullPath());
            }
        }
    }

    private int calculateMaxImageIoErrors(int imagesTotalCount) {
        int maxIoErrors = (int) (((float) imagesTotalCount / 100f) * 5f);
        if (maxIoErrors == 0) {
            maxIoErrors = 1;
        }

        return maxIoErrors;
    }

    /**
     * To avoid saving the same posts every time we need to update LastSavedPostNo in the DB
     */
    private void updateLastSavedPostNo(Loadable loadable, List<Post> newPosts) {
        // Update the latests saved post id in the database
        int lastPostNo = newPosts.get(newPosts.size() - 1).no;
        databaseManager.runTask(databaseSavedThreadManager.updateLastSavedPostNo(loadable.id, lastPostNo));
    }

    /**
     * Calculates how many images we have in total
     */
    private int calculateAmountOfImages(List<Post> newPosts) {
        int count = 0;

        for (Post post : newPosts) {
            for (PostImage postImage : post.images) {
                if (postImage.isInlined) {
                    // Skip inlined files
                    continue;
                }

                ++count;
            }
        }

        return count;
    }

    /**
     * Returns only the posts that we haven't saved yet. Sorts them in ascending order.
     * If a post has at least one image that has not been downloaded yet it will be
     * redownloaded again
     */
    private List<Post> filterAndSortPosts(
            BaseFileManager snapshotFileManager,
            AbstractFile threadSaveDirImages,
            Loadable loadable,
            List<Post> inputPosts
    ) {
        long start = System.currentTimeMillis();

        try {
            // Filter out already saved posts (by lastSavedPostNo)
            int lastSavedPostNo = databaseManager.runTask(databaseSavedThreadManager.getLastSavedPostNo(loadable.id));

            // Use HashSet to avoid duplicates
            Set<Post> filteredPosts = new HashSet<>(inputPosts.size() / 2);

            // lastSavedPostNo == 0 means that we don't have this thread downloaded yet
            if (lastSavedPostNo > 0) {
                for (Post post : inputPosts) {
                    if (!checkWhetherAllPostImagesAreAlreadySaved(snapshotFileManager, threadSaveDirImages, post)) {
                        // Some of the post's images could not be downloaded during the previous download
                        // so we need to download them now
                        if (verboseLogsEnabled) {
                            Logger.d(this,
                                    "Found not downloaded yet images for a post " + post.no + ", for loadable "
                                            + loadable.toShortString()
                            );
                        }

                        filteredPosts.add(post);
                        continue;
                    }

                    if (post.no > lastSavedPostNo) {
                        filteredPosts.add(post);
                    }
                }
            } else {
                filteredPosts.addAll(inputPosts);
            }

            if (filteredPosts.isEmpty()) {
                return Collections.emptyList();
            }

            // And sort them
            List<Post> posts = new ArrayList<>(filteredPosts);
            Collections.sort(posts, postComparator);

            return posts;
        } finally {
            long delta = System.currentTimeMillis() - start;
            String loadableString = loadable.toShortString();

            Logger.d(this,
                    "filterAndSortPosts() completed in " + delta + "ms for loadable " + loadableString + " with "
                            + inputPosts.size() + " posts"
            );
        }
    }

    private boolean checkWhetherAllPostImagesAreAlreadySaved(
            BaseFileManager snapshotFileManager, AbstractFile threadSaveDirImages, Post post
    ) {
        for (PostImage postImage : post.images) {
            if (postImage.isInlined) {
                // Skip inlined files
                continue;
            }

            {
                String originalImageFilename =
                        postImage.serverFilename + "_" + ORIGINAL_FILE_NAME + "." + postImage.extension;

                AbstractFile originalImage = threadSaveDirImages.clone(new FileSegment(originalImageFilename));

                if (!snapshotFileManager.exists(originalImage)) {
                    return false;
                }

                if (!snapshotFileManager.canRead(originalImage)) {
                    if (!snapshotFileManager.delete(originalImage)) {
                        Logger.e(this, "Could not delete originalImage with path " + originalImage.getFullPath());
                    }
                    return false;
                }

                long length = snapshotFileManager.getLength(originalImage);
                if (length == -1L) {
                    Logger.e(this,
                            "originalImage.getLength() returned -1, originalImagePath = " + originalImage.getFullPath()
                    );
                    return false;
                }

                if (length == 0L) {
                    if (!snapshotFileManager.delete(originalImage)) {
                        Logger.e(this, "Could not delete originalImage with path " + originalImage.getFullPath());
                    }
                    return false;
                }
            }

            {
                String thumbnailExtension = StringUtils.extractFileNameExtension(postImage.thumbnailUrl.toString());
                String thumbnailImageFilename =
                        postImage.serverFilename + "_" + THUMBNAIL_FILE_NAME + "." + thumbnailExtension;

                AbstractFile thumbnailImage = threadSaveDirImages.clone(new FileSegment(thumbnailImageFilename));

                if (!snapshotFileManager.exists(thumbnailImage)) {
                    return false;
                }

                if (!snapshotFileManager.canRead(thumbnailImage)) {
                    if (!snapshotFileManager.delete(thumbnailImage)) {
                        Logger.e(this, "Could not delete thumbnailImage with path " + thumbnailImage.getFullPath());
                    }
                    return false;
                }

                long length = snapshotFileManager.getLength(thumbnailImage);
                if (length == -1L) {
                    Logger.e(this,
                            "thumbnailImage.getLength() returned -1, thumbnailImagePath = "
                                    + thumbnailImage.getFullPath()
                    );
                    return false;
                }

                if (length == 0L) {
                    if (!snapshotFileManager.delete(thumbnailImage)) {
                        Logger.e(this, "Could not delete thumbnailImage with path " + thumbnailImage.getFullPath());
                    }
                    return false;
                }
            }
        }

        return true;
    }

    private boolean downloadSpoilerImage(
            BaseFileManager snapshotFileManager,
            Loadable loadable,
            AbstractFile threadSaveDirImages,
            @Nullable HttpUrl spoilerImageUrl
    )
            throws IOException {
        // If the board uses spoiler image - download it
        if (loadable.board.spoilers && spoilerImageUrl != null) {
            String spoilerImageExtension = StringUtils.extractFileNameExtension(spoilerImageUrl.toString());
            if (spoilerImageExtension == null) {
                Logger.e(this,
                        "Could not extract spoiler image extension from url, spoilerImageUrl = "
                                + spoilerImageUrl.toString()
                );
                return false;
            }

            String spoilerImageName = SPOILER_FILE_NAME + "." + spoilerImageExtension;

            AbstractFile spoilerImageFullPath = threadSaveDirImages.clone(new FileSegment(spoilerImageName));
            if (snapshotFileManager.exists(spoilerImageFullPath)) {
                // Do nothing if already downloaded
                return false;
            }

            try {
                downloadImage(snapshotFileManager, threadSaveDirImages, spoilerImageName, spoilerImageUrl);
            } catch (ImageWasAlreadyDeletedException e) {
                // If this ever happens that means that something has changed on the server
                Logger.e(this, "Could not download spoiler image, got 404 for loadable " + loadable.toShortString());
                return false;
            }
        }

        return true;
    }

    @Nullable
    private HttpUrl getSpoilerImageUrl(List<Post> posts) {
        for (Post post : posts) {
            if (post.images.size() > 0) {
                return post.images.get(0).spoilerThumbnailUrl;
            }
        }

        return null;
    }

    private Flowable<Boolean> downloadImages(
            BaseFileManager snapshotFileManager,
            Loadable loadable,
            AbstractFile threadSaveDirImages,
            Post post,
            AtomicInteger currentImageDownloadIndex,
            int imagesTotalCount,
            AtomicInteger imageDownloadsWithIoError,
            int maxImageIoErrors
    ) {
        if (post.images.isEmpty()) {
            if (verboseLogsEnabled) {
                Logger.d(this, "Post " + post.no + " contains no images");
            }
            // No images, so return true
            return Flowable.just(true);
        }

        if (!shouldDownloadImages()) {
            if (verboseLogsEnabled) {
                Logger.d(this, "Cannot load images or videos with the current network");
            }
            return Flowable.just(false);
        }

        return Flowable.fromIterable(post.images)
                // We don't want to download inlined images/files
                .filter((postImage) -> !postImage.isInlined).flatMapSingle(postImage -> {
                    // Download each image in parallel using executorService
                    return Single.defer(() -> downloadInternal(snapshotFileManager,
                            loadable,
                            threadSaveDirImages,
                            imageDownloadsWithIoError,
                            maxImageIoErrors,
                            postImage
                    ))
                            // We don't really want to use a lot of threads here so we use an executor with
                            // specified amount of threads
                            .subscribeOn(Schedulers.from(executorService))
                            // Retry couple of times upon exceptions
                            .retry(MAX_RETRY_ATTEMPTS)
                            .doOnError(error -> {
                                Logger.e(ThreadSaveManager.this,
                                        "Error while trying to download image " + postImage.serverFilename,
                                        error
                                );

                                if (error instanceof IOException) {
                                    imageDownloadsWithIoError.incrementAndGet();
                                }
                            })
                            .doOnEvent((result, event) -> logThreadDownloadingProgress(loadable,
                                    currentImageDownloadIndex,
                                    imagesTotalCount
                            ))
                            // Do nothing if an error occurs (like timeout exception) because we don't want
                            // to lose what we have already downloaded
                            .onErrorReturnItem(false);
                });
    }

    private Single<Boolean> downloadInternal(
            BaseFileManager snapshotFileManager,
            Loadable loadable,
            AbstractFile threadSaveDirImages,
            AtomicInteger imageDownloadsWithIoError,
            int maxImageIoErrors,
            PostImage postImage
    )
            throws IOException {
        if (imageDownloadsWithIoError.get() >= maxImageIoErrors) {
            Logger.d(this, "downloadImages terminated due to amount of IOExceptions");
            return Single.just(false);
        }

        String thumbnailExtension = StringUtils.extractFileNameExtension(postImage.thumbnailUrl.toString());

        if (thumbnailExtension == null) {
            Logger.d(this,
                    "Could not extract thumbnail image extension, thumbnailUrl = " + postImage.thumbnailUrl.toString()
            );
            return Single.just(false);
        }

        if (postImage.imageUrl == null) {
            Logger.d(this, "postImage.imageUrl == null");
            return Single.just(false);
        }

        try {
            downloadImageIntoFile(snapshotFileManager,
                    threadSaveDirImages,
                    postImage.serverFilename,
                    postImage.extension,
                    thumbnailExtension,
                    postImage.imageUrl,
                    postImage.thumbnailUrl,
                    loadable
            );
        } catch (IOException error) {
            Logger.e(this,
                    "downloadImageIntoFile error for image " + postImage.serverFilename + ", error message = %s",
                    error.getMessage()
            );

            deleteImageCompletely(snapshotFileManager,
                    threadSaveDirImages,
                    postImage.serverFilename,
                    postImage.extension
            );

            throw error;
        } catch (ImageWasAlreadyDeletedException error) {
            Logger.e(this,
                    "Could not download an image " + postImage.serverFilename + " for loadable "
                            + loadable.toShortString() + ", got 404, adding it to the deletedImages set"
            );

            addImageToAlreadyDeletedImage(loadable, postImage.serverFilename);

            deleteImageCompletely(snapshotFileManager,
                    threadSaveDirImages,
                    postImage.serverFilename,
                    postImage.extension
            );
            return Single.just(false);
        }

        return Single.just(true);
    }

    private boolean isImageAlreadyDeletedFromServer(Loadable loadable, String filename) {
        synchronized (activeDownloads) {
            AdditionalThreadParameters parameters = additionalThreadParameter.get(loadable);
            if (parameters == null) {
                Logger.e(this,
                        "isImageAlreadyDeletedFromServer parameters == null for loadable " + loadable.toShortString()
                );
                return true;
            }

            return parameters.isImageDeletedFromTheServer(filename);
        }
    }

    private void addImageToAlreadyDeletedImage(Loadable loadable, String originalName) {
        synchronized (activeDownloads) {
            AdditionalThreadParameters parameters = additionalThreadParameter.get(loadable);
            if (parameters == null) {
                Logger.e(this,
                        "addImageToAlreadyDeletedImage parameters == null for loadable " + loadable.toShortString()
                );
                return;
            }

            parameters.addDeletedImage(originalName);
        }
    }

    private void logThreadDownloadingProgress(
            Loadable loadable, AtomicInteger currentImageDownloadIndex, int imagesTotalCount
    ) {
        // imagesTotalCount may be 0 so we need to avoid division by zero
        int count = imagesTotalCount == 0 ? 1 : imagesTotalCount;
        int index = currentImageDownloadIndex.incrementAndGet();
        int percent = (int) (((float) index / (float) count) * 100f);

        Logger.d(this,
                "Downloading is in progress for an image with loadable " + loadable.toShortString() + ", " + index + "/"
                        + count + " (" + percent + "%)"
        );
    }

    private void deleteImageCompletely(
            BaseFileManager snapshotFileManager, AbstractFile threadSaveDirImages, String filename, String extension
    ) {
        Logger.d(this, "Deleting a file with name " + filename);
        boolean error = false;

        AbstractFile originalFile =
                threadSaveDirImages.clone(new FileSegment(filename + "_" + ORIGINAL_FILE_NAME + "." + extension));

        if (snapshotFileManager.exists(originalFile)) {
            if (!snapshotFileManager.delete(originalFile)) {
                error = true;
            }
        }

        AbstractFile thumbnailFile =
                threadSaveDirImages.clone(new FileSegment(filename + "_" + THUMBNAIL_FILE_NAME + "." + extension));

        if (snapshotFileManager.exists(thumbnailFile)) {
            if (!snapshotFileManager.delete(thumbnailFile)) {
                error = true;
            }
        }

        if (error) {
            Logger.e(this, "Could not completely delete image " + filename);
        }
    }

    /**
     * Downloads an image with it's thumbnail and stores them to the disk
     */
    private void downloadImageIntoFile(
            BaseFileManager snapshotFileManager,
            AbstractFile threadSaveDirImages,
            String filename,
            String originalExtension,
            String thumbnailExtension,
            HttpUrl imageUrl,
            HttpUrl thumbnailUrl,
            Loadable loadable
    )
            throws IOException, ImageWasAlreadyDeletedException {
        if (isImageAlreadyDeletedFromServer(loadable, filename)) {
            // We have already tried to download this image and got 404, so it was probably deleted
            // from the server so there is no point in trying to download it again
            Logger.d(this,
                    "Image " + filename + " was already deleted from the server for loadable "
                            + loadable.toShortString()
            );
            return;
        }

        if (verboseLogsEnabled) {
            Logger.d(this,
                    "Downloading a file with name " + filename + " on a thread " + currentThread().getName()
                            + " for loadable " + loadable.toShortString()
            );
        }

        downloadImage(snapshotFileManager,
                threadSaveDirImages,
                filename + "_" + ORIGINAL_FILE_NAME + "." + originalExtension,
                imageUrl
        );
        downloadImage(snapshotFileManager,
                threadSaveDirImages,
                filename + "_" + THUMBNAIL_FILE_NAME + "." + thumbnailExtension,
                thumbnailUrl
        );
    }

    /**
     * Checks whether the user allows downloading images and other files when there is no Wi-Fi connection
     */
    private boolean shouldDownloadImages() {
        return ChanSettings.MediaAutoLoadMode.shouldLoadForNetworkType(ChanSettings.imageAutoLoadNetwork.get())
                && ChanSettings.MediaAutoLoadMode.shouldLoadForNetworkType(ChanSettings.videoAutoLoadNetwork.get());
    }

    /**
     * Downloads an image and stores it to the disk
     */
    private void downloadImage(
            BaseFileManager snapshotFileManager, AbstractFile threadSaveDirImages, String filename, HttpUrl imageUrl
    )
            throws IOException, ImageWasAlreadyDeletedException {
        if (!shouldDownloadImages()) {
            if (verboseLogsEnabled) {
                Logger.d(this, "Cannot load images or videos with the current network");
            }
            return;
        }

        AbstractFile imageFile = threadSaveDirImages.clone(new FileSegment(filename));

        if (!snapshotFileManager.exists(imageFile)) {
            Request request = new Request.Builder().url(imageUrl).build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.code() != 200) {
                    if (response.code() == 404) {
                        throw new ImageWasAlreadyDeletedException(filename);
                    }

                    throw new IOException("Download image request returned bad status code: " + response.code());
                }

                storeImageToFile(snapshotFileManager, imageFile, response);

                if (verboseLogsEnabled) {
                    Logger.d(this, "Downloaded a file with name " + filename);
                }
            }
        } else {
            Logger.d(this, "image " + filename + " already exists on the disk, skip it");
        }
    }

    /**
     * @return true when user removes the pin associated with this loadable
     */
    private boolean shouldDeleteDownloadedFiles(Loadable loadable) {
        synchronized (activeDownloads) {
            SaveThreadParameters parameters = activeDownloads.get(loadable);
            if (parameters != null) {
                if (parameters.isCanceled()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isCurrentDownloadStopped(Loadable loadable) {
        synchronized (activeDownloads) {
            SaveThreadParameters parameters = activeDownloads.get(loadable);
            if (parameters != null) {
                if (parameters.isStopped()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isCurrentDownloadRunning(Loadable loadable) {
        synchronized (activeDownloads) {
            SaveThreadParameters parameters = activeDownloads.get(loadable);
            if (parameters != null) {
                if (parameters.isRunning()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Writes image's bytes to a file
     */
    private void storeImageToFile(BaseFileManager snapshotFileManager, AbstractFile imageFile, Response response)
            throws IOException {
        if (snapshotFileManager.create(imageFile) == null) {
            throw new IOException(
                    "Could not create a file to save an image to (path: " + imageFile.getFullPath() + ")");
        }

        try (ResponseBody body = response.body()) {
            if (body == null) {
                throw new IOException("Response body is null");
            }

            if (body.contentLength() <= 0) {
                throw new IOException("Image body is empty");
            }

            try (InputStream is = body.byteStream()) {
                try (OutputStream os = snapshotFileManager.getOutputStream(imageFile)) {
                    if (os == null) {
                        throw new IOException("Could not get OutputStream, imageFilePath = " + imageFile.getFullPath());
                    }

                    IOUtils.copy(is, os);
                }
            }
        }
    }

    /**
     * Determines whether we should log this exception or not.
     * For instance NoNewPostsToSaveException will be thrown every time when there are no new
     * posts to download left after filtering.
     */
    private boolean isFatalException(Throwable error) {
        return !(error instanceof NoNewPostsToSaveException);
    }

    /**
     * When user cancels a download we need to delete the thread from the disk as well
     */
    private void deleteThreadFilesFromDisk(BaseFileManager snapshotFileManager, Loadable loadable) {
        AbstractFile baseDirectory = fileManager.newBaseDirectoryFile(LocalThreadsBaseDirectory.class);

        if (baseDirectory == null) {
            throw new IllegalStateException("LocalThreadsBaseDirectory is not registered!");
        }

        AbstractFile threadSaveDir = baseDirectory.clone(getThreadSubDir(loadable));
        if (!fileManager.exists(threadSaveDir)) {
            return;
        }

        snapshotFileManager.delete(threadSaveDir);
    }

    public static String formatThumbnailImageName(String originalName, String extension) {
        return originalName + "_" + THUMBNAIL_FILE_NAME + "." + extension;
    }

    public static String formatOriginalImageName(String originalName, String extension) {
        return originalName + "_" + ORIGINAL_FILE_NAME + "." + extension;
    }

    public static String formatSpoilerImageName(String extension) {
        // spoiler.jpg
        return SPOILER_FILE_NAME + "." + extension;
    }

    public static List<Segment> getThreadSubDir(Loadable loadable) {
        // 4chan/g/11223344
        return Arrays.asList(new DirectorySegment(loadable.site.name()),
                new DirectorySegment(loadable.boardCode),
                new DirectorySegment(String.valueOf(loadable.no))
        );
    }

    public static List<Segment> getImagesSubDir(Loadable loadable) {
        // 4chan/g/11223344/images
        return Arrays.asList(new DirectorySegment(loadable.site.name()),
                new DirectorySegment(loadable.boardCode),
                new DirectorySegment(String.valueOf(loadable.no)),
                new DirectorySegment(IMAGES_DIR_NAME)
        );
    }

    public static List<Segment> getBoardSubDir(Loadable loadable) {
        // 4chan/g
        return Arrays.asList(new DirectorySegment(loadable.site.name()), new DirectorySegment(loadable.boardCode));
    }

    /**
     * The main difference between AdditionalThreadParameters and SaveThreadParameters is that
     * SaveThreadParameters is getting deleted after each thread download attempt while
     * AdditionalThreadParameters stay until app restart. We use them to not download 404ed images
     * on each attempt (because it may block the downloading process for up to
     * OKHTTP_TIMEOUT_SECONDS seconds)
     */
    public static class AdditionalThreadParameters {
        private Set<String> deletedImages;

        public AdditionalThreadParameters() {
            this.deletedImages = new HashSet<>();
        }

        public void addDeletedImage(String deletedImageFilename) {
            deletedImages.add(deletedImageFilename);
        }

        public boolean isImageDeletedFromTheServer(String filename) {
            return deletedImages.contains(filename);
        }
    }

    public static class SaveThreadParameters {
        private List<Post> postsToSave;
        private AtomicReference<DownloadRequestState> state;

        public SaveThreadParameters(List<Post> postsToSave) {
            this.postsToSave = postsToSave;
            this.state = new AtomicReference<>(DownloadRequestState.Running);
        }

        public boolean isRunning() {
            return state.get() == DownloadRequestState.Running;
        }

        public boolean isCanceled() {
            return state.get() == DownloadRequestState.Canceled;
        }

        public boolean isStopped() {
            return state.get() == DownloadRequestState.Stopped;
        }

        public void stop() {
            state.compareAndSet(DownloadRequestState.Running, DownloadRequestState.Stopped);
        }

        public void cancel() {
            state.compareAndSet(DownloadRequestState.Running, DownloadRequestState.Canceled);
        }
    }

    static class ImageWasAlreadyDeletedException
            extends Exception {
        public ImageWasAlreadyDeletedException(String fileName) {
            super("Image " + fileName + " was already deleted");
        }
    }

    static class NoNewPostsToSaveException
            extends Exception {
        public NoNewPostsToSaveException() {
            super("No new posts left to save after filtering");
        }
    }

    static class CouldNotCreateThreadDirectoryException
            extends Exception {
        private static String message = "Could not create a directory to save the thread to (full path: %s)";

        public CouldNotCreateThreadDirectoryException(@Nullable AbstractFile threadSaveDir) {
            super(String.format(message, threadSaveDir == null ? "null" : threadSaveDir.getFullPath()));
        }
    }

    static class CouldNotCreateNoMediaFile
            extends Exception {
        public CouldNotCreateNoMediaFile(AbstractFile threadSaveDirImages) {
            super("Could not create .nomedia file in directory " + threadSaveDirImages.getFullPath());
        }
    }

    static class CouldNotCreateImagesDirectoryException
            extends Exception {
        public CouldNotCreateImagesDirectoryException(AbstractFile threadSaveDirImages) {
            super("Could not create a directory to save the thread images to (full path: "
                    + threadSaveDirImages.getFullPath() + ")");
        }
    }

    static class CouldNotCreateSpoilerImageDirectoryException
            extends Exception {
        private static String message = "Could not create a directory to save the spoiler image to (full path: %s)";

        public CouldNotCreateSpoilerImageDirectoryException(@Nullable AbstractFile boardSaveDir) {
            super(String.format(message, boardSaveDir == null ? "null" : boardSaveDir.getFullPath()));
        }
    }

    public enum DownloadRequestState {
        Running,
        Canceled,   // Pin is removed or both buttons (watch posts and save posts) are unpressed.
        Stopped     // Save posts button is unpressed.
    }

    private static final Comparator<Post> postComparator = (o1, o2) -> Integer.compare(o1.no, o2.no);
}
