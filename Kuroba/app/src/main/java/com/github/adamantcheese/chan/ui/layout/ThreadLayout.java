/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.ui.layout;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.github.adamantcheese.chan.R;
import com.github.adamantcheese.chan.controller.Controller;
import com.github.adamantcheese.chan.core.database.DatabaseManager;
import com.github.adamantcheese.chan.core.manager.FilterType;
import com.github.adamantcheese.chan.core.model.ChanThread;
import com.github.adamantcheese.chan.core.model.Post;
import com.github.adamantcheese.chan.core.model.PostHttpIcon;
import com.github.adamantcheese.chan.core.model.PostImage;
import com.github.adamantcheese.chan.core.model.PostLinkable;
import com.github.adamantcheese.chan.core.model.orm.Loadable;
import com.github.adamantcheese.chan.core.model.orm.PostHide;
import com.github.adamantcheese.chan.core.presenter.ReplyPresenter.Page;
import com.github.adamantcheese.chan.core.presenter.ThreadPresenter;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.core.site.loader.ChanThreadLoader;
import com.github.adamantcheese.chan.ui.adapter.PostsFilter;
import com.github.adamantcheese.chan.ui.controller.ImageOptionsController;
import com.github.adamantcheese.chan.ui.helper.PostPopupHelper;
import com.github.adamantcheese.chan.ui.helper.RemovedPostsHelper;
import com.github.adamantcheese.chan.ui.theme.ThemeHelper;
import com.github.adamantcheese.chan.ui.toolbar.Toolbar;
import com.github.adamantcheese.chan.ui.view.HidingFloatingActionButton;
import com.github.adamantcheese.chan.ui.view.LoadView;
import com.github.adamantcheese.chan.ui.view.ThumbnailView;
import com.github.adamantcheese.chan.utils.AndroidUtils;
import com.github.adamantcheese.chan.utils.BackgroundUtils;
import com.github.adamantcheese.chan.utils.LayoutUtils;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import static com.github.adamantcheese.chan.Chan.inject;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getQuantityString;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getString;
import static com.github.adamantcheese.chan.utils.AndroidUtils.hideKeyboard;
import static com.github.adamantcheese.chan.utils.AndroidUtils.openLinkInBrowser;
import static com.github.adamantcheese.chan.utils.AndroidUtils.removeFromParentView;
import static com.github.adamantcheese.chan.utils.AndroidUtils.setClipboardContent;
import static com.github.adamantcheese.chan.utils.AndroidUtils.showToast;

/**
 * Wrapper around ThreadListLayout, so that it cleanly manages between a loading state
 * and the recycler view.
 */
public class ThreadLayout
        extends CoordinatorLayout
        implements ThreadPresenter.ThreadPresenterCallback, PostPopupHelper.PostPopupHelperCallback,
                   RemovedPostsHelper.RemovedPostsCallbacks, View.OnClickListener,
                   ThreadListLayout.ThreadListLayoutCallback, ImageOptionsController.ImageOptionsControllerCallback {
    private enum Visible {
        EMPTY,
        LOADING,
        THREAD,
        ERROR
    }

    @Inject
    DatabaseManager databaseManager;

    @Inject
    ThreadPresenter presenter;

    private ThreadLayoutCallback callback;

    private View progressLayout;

    private LoadView loadView;
    private HidingFloatingActionButton replyButton;
    private ThreadListLayout threadListLayout;
    private LinearLayout errorLayout;
    private boolean archiveButton;

    private TextView errorText;
    private Button errorRetryButton;
    private PostPopupHelper postPopupHelper;
    private RemovedPostsHelper removedPostsHelper;
    private Visible visible;
    private ProgressDialog deletingDialog;
    private boolean replyButtonEnabled;
    private boolean showingReplyButton = false;
    private Snackbar newPostsNotification;

    public ThreadLayout(Context context) {
        this(context, null);
    }

    public ThreadLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ThreadLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        inject(this);
    }

    public void create(ThreadLayoutCallback callback) {
        this.callback = callback;

        // View binding
        loadView = findViewById(R.id.loadview);
        replyButton = findViewById(R.id.reply_button);

        // Inflate ThreadListLayout
        threadListLayout =
                (ThreadListLayout) LayoutUtils.inflate(getContext(), R.layout.layout_thread_list, this, false);

        // Inflate error layout
        errorLayout = (LinearLayout) LayoutUtils.inflate(getContext(), R.layout.layout_thread_error, this, false);
        errorText = errorLayout.findViewById(R.id.text);
        errorRetryButton = errorLayout.findViewById(R.id.button);

        // Inflate thread loading layout
        progressLayout = LayoutUtils.inflate(getContext(), R.layout.layout_thread_progress, this, false);

        // View setup
        presenter.setContext(getContext());
        threadListLayout.setCallbacks(presenter, presenter, presenter, presenter, this);
        postPopupHelper = new PostPopupHelper(getContext(), presenter, this);
        removedPostsHelper = new RemovedPostsHelper(getContext(), presenter, this);
        errorText.setTypeface(ThemeHelper.getTheme().mainFont);
        errorRetryButton.setOnClickListener(this);

        // Setup
        replyButtonEnabled = ChanSettings.enableReplyFab.get();
        if (!replyButtonEnabled) {
            removeFromParentView(replyButton);
        } else {
            replyButton.setOnClickListener(this);
            replyButton.setToolbar(callback.getToolbar());
        }

        presenter.create(this);
    }

    public void destroy() {
        presenter.unbindLoadable();
    }

    @Override
    public void onClick(View v) {
        if (v == errorRetryButton) {
            if (!archiveButton) {
                presenter.requestData();
            } else {
                callback.showArchives();
            }
        } else if (v == replyButton) {
            threadListLayout.openReply(true);
        }
    }

    public boolean canChildScrollUp() {
        if (visible == Visible.THREAD) {
            return threadListLayout.canChildScrollUp();
        } else {
            return true;
        }
    }

    public boolean onBack() {
        return threadListLayout.onBack();
    }

    public boolean sendKeyEvent(KeyEvent event) {
        return threadListLayout.sendKeyEvent(event);
    }

    public ThreadPresenter getPresenter() {
        return presenter;
    }

    public void gainedFocus() {
        if (visible == Visible.THREAD) {
            threadListLayout.gainedFocus();
        }
    }

    public void setPostViewMode(ChanSettings.PostViewMode postViewMode) {
        threadListLayout.setPostViewMode(postViewMode);
    }

    @Override
    public void replyLayoutOpen(boolean open) {
        showReplyButton(!open);
    }

    @Override
    public Toolbar getToolbar() {
        return callback.getToolbar();
    }

    @Override
    public boolean threadBackPressed() {
        return callback.threadBackPressed();
    }

    @Override
    public void showPosts(
            ChanThread thread, PostsFilter filter, boolean refreshAfterHideOrRemovePosts
    ) {
        if (thread.getLoadable().isLocal()) {
            if (replyButton.getVisibility() == VISIBLE) {
                replyButton.hide();
            }
        } else {
            if (replyButton.getVisibility() != VISIBLE) {
                replyButton.show();
            }
        }

        getPresenter().updateLoadable(thread.getLoadable().getLoadableDownloadingState());
        threadListLayout.showPosts(thread, filter, visible != Visible.THREAD, refreshAfterHideOrRemovePosts);

        switchVisible(Visible.THREAD);
        callback.onShowPosts();
    }

    @Override
    public void postClicked(Post post) {
        if (postPopupHelper.isOpen()) {
            postPopupHelper.postClicked(post);
        }
    }

    @Override
    public void showError(ChanThreadLoader.ChanLoaderException error) {
        String errorMessage = getString(error.getErrorMessage());

        if (visible == Visible.THREAD) {
            threadListLayout.showError(errorMessage);
        } else {
            switchVisible(Visible.ERROR);
            errorText.setText(errorMessage);
            if (error.getErrorMessage() == R.string.thread_load_failed_not_found) {
                errorRetryButton.setText(R.string.thread_show_archives);
                archiveButton = true;

                presenter.markAllPostsAsSeen();
            }
        }
    }

    @Override
    public void showLoading() {
        switchVisible(Visible.LOADING);
    }

    @Override
    public void showEmpty() {
        switchVisible(Visible.EMPTY);
    }

    public void showPostInfo(String info) {
        new AlertDialog.Builder(getContext()).setTitle(R.string.post_info_title)
                .setMessage(info)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    public void showPostLinkables(final Post post) {
        final List<PostLinkable> linkables = post.linkables;
        String[] keys = new String[linkables.size()];
        for (int i = 0; i < linkables.size(); i++) {
            keys[i] = linkables.get(i).key.toString();
        }

        new AlertDialog.Builder(getContext()).setItems(keys,
                (dialog, which) -> presenter.onPostLinkableClicked(post, linkables.get(which))
        ).show();
    }

    public void clipboardPost(Post post) {
        setClipboardContent("Post text", post.comment.toString());
        showToast(getContext(), R.string.post_text_copied);
    }

    @Override
    public void openLink(final String link) {
        if (ChanSettings.openLinkConfirmation.get()) {
            new AlertDialog.Builder(getContext()).setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, (dialog, which) -> openLinkConfirmed(link))
                    .setTitle(R.string.open_link_confirmation)
                    .setMessage(link)
                    .show();
        } else {
            openLinkConfirmed(link);
        }
    }

    public void openLinkConfirmed(final String link) {
        if (ChanSettings.openLinkBrowser.get()) {
            AndroidUtils.openLink(link);
        } else {
            openLinkInBrowser(getContext(), link);
        }
    }

    @Override
    public void openReportView(Post post) {
        callback.openReportController(post);
    }

    @Override
    public void showThread(Loadable threadLoadable) {
        callback.showThread(threadLoadable);
    }

    @Override
    public void showBoard(Loadable catalogLoadable) {
        callback.showBoard(catalogLoadable);
    }

    @Override
    public void showBoardAndSearch(Loadable catalogLoadable, String searchQuery) {
        callback.showBoardAndSearch(catalogLoadable, searchQuery);
    }

    public void showPostsPopup(Post forPost, List<Post> posts) {
        if (this.getFocusedChild() != null) {
            View currentFocus = this.getFocusedChild();
            hideKeyboard(currentFocus);
            currentFocus.clearFocus();
        }
        postPopupHelper.showPosts(forPost, posts);
    }

    @Override
    public void hidePostsPopup() {
        postPopupHelper.popAll();
    }

    @Override
    public List<Post> getDisplayingPosts() {
        if (postPopupHelper.isOpen()) {
            return postPopupHelper.getDisplayingPosts();
        } else {
            return threadListLayout.getDisplayingPosts();
        }
    }

    @Override
    public int[] getCurrentPosition() {
        return threadListLayout.getIndexAndTop();
    }

    @Override
    public void showImages(List<PostImage> images, int index, Loadable loadable, ThumbnailView thumbnail) {
        if (this.getFocusedChild() != null) {
            View currentFocus = this.getFocusedChild();
            hideKeyboard(currentFocus);
            currentFocus.clearFocus();
        }
        callback.showImages(images, index, loadable, thumbnail);
    }

    @Override
    public void showAlbum(List<PostImage> images, int index) {
        callback.showAlbum(images, index);
    }

    @Override
    public void scrollTo(int displayPosition, boolean smooth) {
        if (postPopupHelper.isOpen()) {
            postPopupHelper.scrollTo(displayPosition, smooth);
        } else if (visible == Visible.THREAD) {
            threadListLayout.scrollTo(displayPosition, smooth);
        }
    }

    @Override
    public void smoothScrollNewPosts(int displayPosition) {
        threadListLayout.smoothScrollNewPosts(displayPosition);
    }

    @Override
    public void highlightPost(Post post) {
        threadListLayout.highlightPost(post);
    }

    @Override
    public void highlightPostId(String id) {
        threadListLayout.highlightPostId(id);
    }

    @Override
    public void highlightPostTripcode(String tripcode) {
        threadListLayout.highlightPostTripcode(tripcode);
    }

    @Override
    public void filterPostSubject(String subject) {
        callback.openFilterForType(FilterType.SUBJECT, subject);
    }

    @Override
    public void filterPostName(String name) {
        callback.openFilterForType(FilterType.NAME, name);
    }

    @Override
    public void filterPostID(String id) {
        callback.openFilterForType(FilterType.ID, id);
    }

    @Override
    public void filterPostComment(CharSequence comment) {
        callback.openFilterForType(FilterType.COMMENT, comment.toString());
    }

    @Override
    public void filterPostCountryCode(Post post) {
        String countryCode = "";
        if (post.httpIcons != null && !post.httpIcons.isEmpty()) {
            for (PostHttpIcon icon : post.httpIcons) {
                if (icon.url.toString().contains("troll") || icon.url.toString().contains("country")) {
                    countryCode = icon.name.substring(icon.name.indexOf('/') + 1);
                    break;
                }
            }
        }
        callback.openFilterForType(FilterType.COUNTRY_CODE, countryCode);
    }

    @Override
    public void filterPostFilename(Post post) {
        if (post.images.isEmpty()) return;
        callback.openFilterForType(FilterType.FILENAME, post.image().filename);
    }

    @Override
    public void filterPostTripcode(String tripcode) {
        callback.openFilterForType(FilterType.TRIPCODE, tripcode);
    }

    @Override
    public void filterPostImageHash(Post post) {
        if (post.images.isEmpty()) return;
        if (post.images.size() == 1) {
            callback.openFilterForType(FilterType.IMAGE, post.image().fileHash);
        } else {
            ListView hashList = new ListView(getContext());
            AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle("Select an image to filter.")
                    .setView(hashList)
                    .create();
            dialog.setCanceledOnTouchOutside(true);
            List<String> hashes = new ArrayList<>();
            for (PostImage image : post.images) {
                if (!image.isInlined && image.fileHash != null) hashes.add(image.fileHash);
            }
            hashList.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, hashes));
            hashList.setOnItemClickListener((parent, view, position, id) -> {
                callback.openFilterForType(FilterType.IMAGE, hashes.get(position));
                dialog.dismiss();
            });

            dialog.show();
        }
    }

    @Override
    public void selectPost(int post) {
        threadListLayout.selectPost(post);
    }

    @Override
    public void showSearch(boolean show) {
        threadListLayout.openSearch(show);
    }

    public void setSearchStatus(String query, boolean setEmptyText, boolean hideKeyboard) {
        threadListLayout.setSearchStatus(query, setEmptyText, hideKeyboard);
    }

    @Override
    public void quote(Post post, boolean withText) {
        threadListLayout.openReply(true);
        threadListLayout.getReplyPresenter().quote(post, withText);
    }

    @Override
    public void quote(Post post, CharSequence text) {
        threadListLayout.openReply(true);
        threadListLayout.getReplyPresenter().quote(post, text);
    }

    @Override
    public void confirmPostDelete(final Post post) {
        @SuppressLint("InflateParams")
        final View view = LayoutUtils.inflate(getContext(), R.layout.dialog_post_delete, null);
        CheckBox checkBox = view.findViewById(R.id.image_only);
        new AlertDialog.Builder(getContext()).setTitle(R.string.delete_confirm)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete,
                        (dialog, which) -> presenter.deletePostConfirmed(post, checkBox.isChecked())
                )
                .show();
    }

    @Override
    public void showDeleting() {
        if (deletingDialog == null) {
            deletingDialog = ProgressDialog.show(getContext(), null, getString(R.string.delete_wait));
        }
    }

    @Override
    public void hideDeleting(String message) {
        if (deletingDialog != null) {
            deletingDialog.dismiss();
            deletingDialog = null;

            new AlertDialog.Builder(getContext()).setMessage(message).setPositiveButton(R.string.ok, null).show();
        }
    }

    @Override
    public void hideThread(Post post, int threadNo, boolean hide) {
        // hideRepliesToThisPost is false here because we don't have posts in the catalog mode so there
        // is no point in hiding replies to a thread
        final PostHide postHide = PostHide.hidePost(post, true, hide, false);

        databaseManager.runTask(databaseManager.getDatabaseHideManager().addThreadHide(postHide));

        presenter.refreshUI();

        int snackbarStringId = hide ? R.string.thread_hidden : R.string.thread_removed;

        Snackbar snackbar = Snackbar.make(this, snackbarStringId, Snackbar.LENGTH_LONG);
        snackbar.setGestureInsetBottomIgnored(true);
        snackbar.setAction(R.string.undo, v -> {
            databaseManager.runTask(databaseManager.getDatabaseHideManager().removePostHide(postHide));
            presenter.refreshUI();
        }).show();
    }

    @Override
    public void hideOrRemovePosts(boolean hide, boolean wholeChain, Set<Post> posts, int threadNo) {
        final List<PostHide> hideList = new ArrayList<>();

        for (Post post : posts) {
            // Do not add the OP post to the hideList since we don't want to hide an OP post
            // while being in a thread (it just doesn't make any sense)
            if (!post.isOP) {
                hideList.add(PostHide.hidePost(post, false, hide, wholeChain));
            }
        }

        databaseManager.runTask(databaseManager.getDatabaseHideManager().addPostsHide(hideList));

        presenter.refreshUI();

        String formattedString;
        if (hide) {
            formattedString = getQuantityString(R.plurals.post_hidden, posts.size(), posts.size());
        } else {
            formattedString = getQuantityString(R.plurals.post_removed, posts.size(), posts.size());
        }

        Snackbar snackbar = Snackbar.make(this, formattedString, Snackbar.LENGTH_LONG);
        snackbar.setGestureInsetBottomIgnored(true);
        snackbar.setAction(R.string.undo, v -> {
            databaseManager.runTask(databaseManager.getDatabaseHideManager().removePostsHide(hideList));
            presenter.refreshUI();
        }).show();
    }

    @Override
    public void unhideOrUnremovePost(Post post) {
        databaseManager.runTask(databaseManager.getDatabaseHideManager().removePostHide(PostHide.unhidePost(post)));

        presenter.refreshUI();
    }

    @Override
    public void viewRemovedPostsForTheThread(List<Post> threadPosts, int threadNo) {
        removedPostsHelper.showPosts(threadPosts, threadNo);
    }

    @Override
    public void onRestoreRemovedPostsClicked(Loadable threadLoadable, List<Integer> selectedPosts) {

        List<PostHide> postsToRestore = new ArrayList<>();

        for (Integer postNo : selectedPosts) {
            postsToRestore.add(PostHide.unhidePost(threadLoadable.site.id(), threadLoadable.boardCode, postNo));
        }

        databaseManager.runTask(databaseManager.getDatabaseHideManager().removePostsHide(postsToRestore));

        presenter.refreshUI();

        Snackbar snackbar =
                Snackbar.make(this, getString(R.string.restored_n_posts, postsToRestore.size()), Snackbar.LENGTH_LONG);
        snackbar.setGestureInsetBottomIgnored(true);
        snackbar.show();
    }

    @Override
    public void showNewPostsNotification(boolean show, int more) {
        if (show) {
            if (!threadListLayout.scrolledToBottom() && BackgroundUtils.isInForeground()
                    && threadListLayout.getReplyPresenter().getPage() == Page.INPUT) {
                String text = getQuantityString(R.plurals.thread_new_posts, more, more);
                dismissSnackbar();
                newPostsNotification = Snackbar.make(this, text, Snackbar.LENGTH_LONG);
                newPostsNotification.setGestureInsetBottomIgnored(true);
                newPostsNotification.setAction(R.string.thread_new_posts_goto, v -> {
                    presenter.onNewPostsViewClicked();
                    dismissSnackbar();
                }).show();
            } else {
                dismissSnackbar();
            }
        } else {
            dismissSnackbar();
        }
    }

    private void dismissSnackbar() {
        if (newPostsNotification != null) {
            newPostsNotification.dismiss();
            newPostsNotification = null;
        }
    }

    private Loadable getLoadable() {
        return presenter.getLoadable();
    }

    @Override
    public void onDetachedFromWindow() {
        dismissSnackbar();
        super.onDetachedFromWindow();
    }

    @Override
    public void showImageReencodingWindow() {
        if (this.getFocusedChild() != null) {
            View currentFocus = this.getFocusedChild();
            hideKeyboard(currentFocus);
            currentFocus.clearFocus();
        }
        try {
            presentController(new ImageOptionsController(getContext(), getLoadable(), this));
        } catch (Exception e) {
            showToast(getContext(), R.string.file_cannot_be_reencoded, Toast.LENGTH_LONG);
        }
    }

    public ThumbnailView getThumbnail(PostImage postImage) {
        if (postPopupHelper.isOpen()) {
            return postPopupHelper.getThumbnail(postImage);
        } else {
            return threadListLayout.getThumbnail(postImage);
        }
    }

    public void openReply(boolean open) {
        threadListLayout.openReply(open);
    }

    private void showReplyButton(final boolean show) {
        if (show != showingReplyButton && replyButtonEnabled) {
            showingReplyButton = show;
            replyButton.animate().cancel();
            replyButton.animate()
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .setStartDelay(show ? 100 : 0)
                    .setDuration(200)
                    .alpha(show ? 1f : 0f)
                    .scaleX(show ? 1f : 0f)
                    .scaleY(show ? 1f : 0f)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationCancel(Animator animation) {
                            replyButton.setAlpha(show ? 1f : 0f);
                            replyButton.setScaleX(show ? 1f : 0f);
                            replyButton.setScaleY(show ? 1f : 0f);
                            replyButton.setClickable(show);
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            replyButton.setClickable(show);
                        }
                    })
                    .start();
        }
    }

    private void switchVisible(Visible visible) {
        if (this.visible != visible) {
            if (this.visible != null) {
                if (this.visible == Visible.THREAD) {
                    threadListLayout.cleanup();
                    postPopupHelper.popAll();
                    if (getLoadable() == null || getLoadable().isThreadMode()) {
                        showSearch(false);
                    }
                    dismissSnackbar();
                }
            }

            this.visible = visible;
            showReplyButton(false);
            callback.hideSwipeRefreshLayout();
            switch (visible) {
                case EMPTY:
                    loadView.setView(inflateEmptyView());
                    break;
                case LOADING:
                    loadView.setView(progressLayout);
                    break;
                case THREAD:
                    loadView.setView(threadListLayout);
                    showReplyButton(true);
                    break;
                case ERROR:
                    loadView.setView(errorLayout);
                    break;
            }
        }
    }

    @SuppressLint("InflateParams")
    private View inflateEmptyView() {
        View view = LayoutUtils.inflate(getContext(), R.layout.layout_empty_setup, null);
        TextView tv = view.findViewById(R.id.feature);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // This unicode symbol crashes app on APIs below 23
            //😴 sleeping face emoji
            tv.setText("\uD83D\uDE34");
        }

        return view;
    }

    @Override
    public void presentController(Controller controller) {
        callback.presentController(controller);
    }

    @Override
    public void onImageOptionsApplied() {
        threadListLayout.onImageOptionsApplied();
    }

    @Override
    public void onImageOptionsComplete() {
        threadListLayout.onImageOptionsComplete();
    }

    @Override
    public void showHideOrRemoveWholeChainDialog(boolean hide, Post post, int threadNo) {
        String positiveButtonText = hide
                ? getString(R.string.thread_layout_hide_whole_chain)
                : getString(R.string.thread_layout_remove_whole_chain);
        String negativeButtonText =
                hide ? getString(R.string.thread_layout_hide_post) : getString(R.string.thread_layout_remove_post);
        String message = hide
                ? getString(R.string.thread_layout_hide_whole_chain_as_well)
                : getString(R.string.thread_layout_remove_whole_chain_as_well);

        AlertDialog alertDialog = new AlertDialog.Builder(getContext()).setMessage(message)
                .setPositiveButton(positiveButtonText,
                        (dialog, which) -> presenter.hideOrRemovePosts(hide, true, post, threadNo)
                )
                .setNegativeButton(negativeButtonText,
                        (dialog, which) -> presenter.hideOrRemovePosts(hide, false, post, threadNo)
                )
                .create();

        alertDialog.show();
    }

    public interface ThreadLayoutCallback {
        void showThread(Loadable threadLoadable);

        void showBoard(Loadable catalogLoadable);

        void showBoardAndSearch(Loadable catalogLoadable, String searchQuery);

        void showArchives();

        void showImages(List<PostImage> images, int index, Loadable loadable, ThumbnailView thumbnail);

        void showAlbum(List<PostImage> images, int index);

        void onShowPosts();

        void presentController(Controller controller);

        void openReportController(Post post);

        void hideSwipeRefreshLayout();

        Toolbar getToolbar();

        void openFilterForType(FilterType type, String filterText);

        boolean threadBackPressed();
    }
}
