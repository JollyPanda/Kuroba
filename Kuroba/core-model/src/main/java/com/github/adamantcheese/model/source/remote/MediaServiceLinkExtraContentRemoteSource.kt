package com.github.adamantcheese.model.source.remote

import com.github.adamantcheese.common.ModularResult
import com.github.adamantcheese.common.ModularResult.Companion.safeRun
import com.github.adamantcheese.model.common.Logger
import com.github.adamantcheese.model.data.MediaServiceLinkExtraInfo
import com.github.adamantcheese.model.data.video_service.MediaServiceType
import com.github.adamantcheese.model.util.ensureBackgroundThread
import com.github.adamantcheese.model.util.errorMessageOrClassName
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

open class MediaServiceLinkExtraContentRemoteSource(
        okHttpClient: OkHttpClient,
        loggerTag: String,
        logger: Logger
) : AbstractRemoteSource(okHttpClient, logger) {
    private val TAG = "$loggerTag MediaServiceLinkExtraContentRemoteSource"

    open suspend fun fetchFromNetwork(
            requestUrl: String,
            mediaServiceType: MediaServiceType
    ): ModularResult<MediaServiceLinkExtraInfo> {
        logger.log(TAG, "fetchFromNetwork($requestUrl, $mediaServiceType)")
        ensureBackgroundThread()

        return safeRun {
            val httpRequest = Request.Builder()
                    .url(requestUrl)
                    .get()
                    .build()

            val response = okHttpClient.suspendCall(httpRequest)
            return@safeRun extractMediaServiceLinkExtraInfo(
                    mediaServiceType,
                    response
            )
        }
    }

    private fun extractMediaServiceLinkExtraInfo(
            mediaServiceType: MediaServiceType,
            response: Response
    ): MediaServiceLinkExtraInfo {
        ensureBackgroundThread()

        return response.use { resp ->
            return@use resp.body.use { body ->
                if (body == null) {
                    return MediaServiceLinkExtraInfo.empty()
                }

                val parser = JsonParser.parseString(body.string())

                val title = MediaServiceLinkExtraContentRemoteSourceHelper
                        .tryExtractVideoTitle(mediaServiceType, parser)
                        .peekError { error ->
                            logger.logError(TAG, "Error while trying to extract video " +
                                    "title for service ($mediaServiceType), " +
                                    "error = ${error.errorMessageOrClassName()}")
                        }
                        .valueOrNull()
                val duration = MediaServiceLinkExtraContentRemoteSourceHelper
                        .tryExtractVideoDuration(mediaServiceType, parser)
                        .peekError { error ->
                            logger.logError(TAG, "Error while trying to extract video " +
                                    "duration for service ($mediaServiceType), " +
                                    "error = ${error.errorMessageOrClassName()}")
                        }
                        .valueOrNull()

                return@use MediaServiceLinkExtraInfo(title, duration)
            }
        }
    }
}