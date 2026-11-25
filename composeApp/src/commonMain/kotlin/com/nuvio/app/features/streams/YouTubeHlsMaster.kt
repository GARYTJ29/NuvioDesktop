package com.nuvio.app.features.streams

/**
 * Picks one video variant and its audio rendition out of a YouTube HLS master playlist.
 *
 * Handing mpv the master playlist makes ffmpeg open every variant (about 15 of them) before
 * playback starts, which takes ~10 seconds. A single variant plus its audio, joined with an
 * mpv `edl://` URL, starts in a few seconds.
 */
internal object YouTubeHlsMaster {

    data class Selection(val videoUrl: String, val audioUrl: String)

    fun select(masterPlaylist: String): Selection? {
        val audioByGroup = mutableMapOf<String, String>()
        val variants = mutableListOf<Variant>()
        val lines = masterPlaylist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val group = attrs["GROUP-ID"]
                    val uri = attrs["URI"]
                    if (attrs["TYPE"] == "AUDIO" && group != null && uri != null) {
                        audioByGroup.putIfAbsent(group, uri)
                    }
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val url = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                    val attrs = attributes(line.substringAfter(':'))
                    val group = attrs["AUDIO"] ?: return@forEachIndexed
                    variants += Variant(
                        url = url,
                        audioGroup = group,
                        height = attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0,
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        isAvc = attrs["CODECS"]?.startsWith("avc1") == true,
                    )
                }
            }
        }

        // Highest resolution; at equal height prefer H.264 (hardware decoding everywhere), then bitrate.
        val best = variants
            .filter { audioByGroup.containsKey(it.audioGroup) }
            .maxWithOrNull(compareBy<Variant>({ it.height }, { it.isAvc }, { it.bandwidth }))
            ?: return null
        return Selection(videoUrl = best.url, audioUrl = audioByGroup.getValue(best.audioGroup))
    }

    /** An mpv EDL URL that plays [Selection.videoUrl] with [Selection.audioUrl] as its audio track. */
    fun mpvEdlUrl(selection: Selection): String =
        "edl://!no_clip;!no_chapters;${edlPart(selection.videoUrl)};" +
            "!new_stream;!no_clip;!no_chapters;${edlPart(selection.audioUrl)}"

    // %len% quoting keeps ';' and ',' in the URL from being read as EDL separators. mpv counts bytes.
    private fun edlPart(url: String): String = "%${url.encodeToByteArray().size}%$url"

    private fun attributes(list: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < list.length) {
            val eq = list.indexOf('=', index)
            if (eq < 0) break
            val key = list.substring(index, eq).trim()
            var valueEnd: Int
            val value = if (list.getOrNull(eq + 1) == '"') {
                val close = list.indexOf('"', eq + 2).let { if (it < 0) list.length else it }
                valueEnd = close + 1
                list.substring(eq + 2, close)
            } else {
                valueEnd = list.indexOf(',', eq + 1).let { if (it < 0) list.length else it }
                list.substring(eq + 1, valueEnd)
            }
            result[key] = value
            index = list.indexOf(',', valueEnd).let { if (it < 0) list.length else it + 1 }
        }
        return result
    }

    private data class Variant(
        val url: String,
        val audioGroup: String,
        val height: Int,
        val bandwidth: Long,
        val isAvc: Boolean,
    )
}
