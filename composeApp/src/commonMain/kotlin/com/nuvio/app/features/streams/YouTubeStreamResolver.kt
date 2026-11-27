package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.TrailerPlaybackMode
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException

/**
 * Makes streams that only carry a YouTube id ([StreamItem.ytId]) playable.
 *
 * With in-app YouTube stream playback the video is resolved on the device to a URL the
 * player can open. Without it, the stream becomes an external link to the
 * video's watch page.
 */
class YouTubeStreamResolver(
    private val inAppPlaybackEnabled: Boolean,
    /** Turns the resolved HLS master playlist URL into the URL handed to the player. */
    private val toPlayerUrl: suspend (String) -> String = { it },
    private val extractSingleUrl: suspend (String) -> String?,
) {
    /**
     * Returns [stream] unchanged when it doesn't need resolving, a copy that can
     * be played when it does, or null when the video couldn't be resolved.
     */
    suspend fun resolve(stream: StreamItem): StreamItem? {
        val videoId = stream.youTubeIdToResolve ?: return stream
        val watchUrl = watchUrl(videoId)
        if (!inAppPlaybackEnabled) return stream.copy(externalUrl = watchUrl)
        val url = extractSingleUrl(watchUrl)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return stream.copy(url = toPlayerUrl(url))
    }

    companion object {
        val Default: YouTubeStreamResolver by lazy {
            YouTubeStreamResolver(
                inAppPlaybackEnabled = AppFeaturePolicy.youTubeStreamPlaybackMode == TrailerPlaybackMode.IN_APP,
                toPlayerUrl = { masterUrl -> if (isDesktop) mpvSingleVariantUrl(masterUrl) else masterUrl },
                extractSingleUrl = { url -> TrailerPlaybackResolver.resolveSingleUrlFromYouTubeUrl(url) },
            )
        }

        /**
         * Desktop plays through mpv, which is slow to start on YouTube's master playlist; give it
         * one variant and its audio instead. Falls back to the master playlist on any failure.
         */
        private suspend fun mpvSingleVariantUrl(masterUrl: String): String {
            val selection = try {
                YouTubeHlsMaster.select(httpGetText(masterUrl))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.withTag("YouTubeStream").w(error) { "Reading the HLS master playlist failed" }
                null
            }
            return selection?.let(YouTubeHlsMaster::mpvEdlUrl) ?: masterUrl
        }

        fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
    }
}
