package com.github.adamantcheese.chan.core.manager

import android.annotation.SuppressLint
import androidx.annotation.GuardedBy
import com.github.adamantcheese.chan.core.manager.loader.OnDemandContentLoader
import com.github.adamantcheese.chan.core.model.Post
import com.github.adamantcheese.chan.core.model.orm.Loadable
import com.github.adamantcheese.chan.utils.BackgroundUtils
import com.github.adamantcheese.chan.utils.Logger
import io.reactivex.Flowable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.processors.PublishProcessor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class OnDemandContentLoaderManager(
        private val scheduler: Scheduler,
        private val loaders: Set<OnDemandContentLoader>
) {
    private val rwLock = ReentrantReadWriteLock()

    // HashMap<PostUid, PostLoaderData>()
    @GuardedBy("rwLock")
    private val activeLoaders = hashMapOf<String, PostLoaderData>()

    private val postLoaderRxQueue = PublishProcessor.create<PostLoaderData>()
    private val postUpdateRxQueue = PublishProcessor.create<OnDemandContentLoader.LoaderBatchResult>()

    init {
        Logger.d(TAG, "Loaders count = ${loaders.size}")
        initPostLoaderRxQueue()
    }

    @SuppressLint("CheckResult")
    private fun initPostLoaderRxQueue() {
        postLoaderRxQueue
                .onBackpressureBuffer(32, false, true)
                .flatMap { value ->
                    return@flatMap Flowable.just(value)
                            .zipWith(Flowable.timer(1, TimeUnit.SECONDS, scheduler), zipper)
                }
                .filter { (postLoaderData, _) -> isStillActive(postLoaderData) }
                .map { (postLoaderData, _) -> postLoaderData }
                .flatMap { postLoaderData -> processLoaders(postLoaderData) }
                .subscribe({
                    // Do nothing
                }, { error ->
                    throw RuntimeException("$TAG Uncaught exception!!! " +
                            "workerQueue is in error state now!!! " +
                            "This should not happen!!!, original error = " + error.message)
                }, {
                    throw RuntimeException(
                            "$TAG workerQueue stream has completed!!! This should not happen!!!"
                    )
                })
    }

    private fun processLoaders(postLoaderData: PostLoaderData): Flowable<Unit>? {
        return Flowable.fromIterable(loaders)
                .flatMapSingle { loader ->
                    return@flatMapSingle loader.startLoading(postLoaderData.loadable, postLoaderData.post)
                            .onErrorReturnItem(OnDemandContentLoader.LoaderResult.Error(loader.loaderType))
                }
                .toList()
                .map { results ->
                    OnDemandContentLoader.LoaderBatchResult(
                            postLoaderData.loadable,
                            postLoaderData.post, results
                    )
                }
                .doOnSuccess(postUpdateRxQueue::onNext)
                .map { Unit }
                .toFlowable()
    }

    fun onPostBind(loadable: Loadable, post: Post) {
        BackgroundUtils.ensureMainThread()
        check(loaders.isNotEmpty()) { "No loaders!" }

        if (everythingIsAlreadyCached(loadable, post)) {
            return
        }

        val postUid = getPostUniqueId(loadable, post)
        val postLoaderData = rwLock.write {
            if (activeLoaders.containsKey(postUid)) {
                return@write null
            }

            val postLoaderData = PostLoaderData(loadable, post)
            activeLoaders[postUid] = postLoaderData

            return@write postLoaderData
        }

        if (postLoaderData == null) {
            return
        }

        postLoaderRxQueue.onNext(postLoaderData)
    }

    fun onPostUnbind(loadable: Loadable, post: Post) {
        BackgroundUtils.ensureMainThread()
        check(loaders.isNotEmpty()) { "No loaders!" }

        val postUid = getPostUniqueId(loadable, post)
        val postLoaderData = rwLock.write { activeLoaders.remove(postUid) }
                ?: return

        postLoaderData.disposeAll()
    }

    fun listenPostContentUpdates(): Flowable<OnDemandContentLoader.LoaderBatchResult> {
        BackgroundUtils.ensureMainThread()

        return postUpdateRxQueue
                .hide()
                .observeOn(AndroidSchedulers.mainThread())
    }

    fun cancelAllForLoadable(loadable: Loadable) {
        BackgroundUtils.ensureMainThread()

        // TODO
    }

    private fun everythingIsAlreadyCached(loadable: Loadable, post: Post): Boolean {
        val allLoadersAlreadyCached = loaders.all { loader ->
            loader.isAlreadyCached(loadable, post)
        }

        if (allLoadersAlreadyCached) {
            val results = loaders.map { loader ->
                OnDemandContentLoader.LoaderResult.Success(loader.loaderType)
            }

            postUpdateRxQueue.onNext(
                    OnDemandContentLoader.LoaderBatchResult(loadable, post, results)
            )

            return true
        }

        return false
    }

    private fun isStillActive(postLoaderData: PostLoaderData): Boolean {
        return rwLock.read { activeLoaders.containsKey(postLoaderData.getPostUniqueId()) }
    }

    class PostLoaderData(
            val loadable: Loadable,
            val post: Post,
            private val disposeFuncList: MutableList<() -> Unit> = mutableListOf()
    ) {

        fun getPostUniqueId(): String {
            return getPostUniqueId(loadable, post)
        }

        @Synchronized
        fun addDisposeFunc(disposeFunc: () -> Unit) {
            disposeFuncList += disposeFunc
        }

        @Synchronized
        fun disposeAll() {
            disposeFuncList.forEach { func -> func.invoke() }
            disposeFuncList.clear()
        }

    }

    companion object {
        private const val TAG = "OnDemandContentLoaderManager"

        private val zipper = BiFunction<PostLoaderData, Long, Pair<PostLoaderData, Long>> { postLoaderData, timer ->
            Pair(postLoaderData, timer)
        }

        private fun getPostUniqueId(loadable: Loadable, post: Post): String {
            return String.format("%s_%d", loadable.uniqueId, post.no)
        }
    }
}