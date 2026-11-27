package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeHlsMasterTest {

    private val master = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:URI="https://example.test/audio/233",TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://example.test/audio/234",TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=289483,CODECS="avc1.4D4015,mp4a.40.5",RESOLUTION=426x240,FRAME-RATE=30,AUDIO="233",CLOSED-CAPTIONS=NONE
        https://example.test/video/240-avc
        #EXT-X-STREAM-INF:BANDWIDTH=3800287,CODECS="avc1.64002A,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=60,AUDIO="234",CLOSED-CAPTIONS=NONE
        https://example.test/video/1080-avc
        #EXT-X-STREAM-INF:BANDWIDTH=2676752,CODECS="vp09.00.41.08,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=60,AUDIO="234",CLOSED-CAPTIONS=NONE
        https://example.test/video/1080-vp9
        #EXT-X-STREAM-INF:BANDWIDTH=1548654,CODECS="vp09.00.40.08,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=60,AUDIO="234",CLOSED-CAPTIONS=NONE
        https://example.test/video/720-vp9
    """.trimIndent()

    @Test
    fun `picks the highest resolution, preferring H264, with its audio group`() {
        assertEquals(
            YouTubeHlsMaster.Selection(
                videoUrl = "https://example.test/video/1080-avc",
                audioUrl = "https://example.test/audio/234",
            ),
            YouTubeHlsMaster.select(master),
        )
    }

    @Test
    fun `skips variants whose audio group is missing`() {
        val withoutAudio234 = master.lines().filterNot { it.contains("GROUP-ID=\"234\"") }.joinToString("\n")
        assertEquals(
            YouTubeHlsMaster.Selection(
                videoUrl = "https://example.test/video/240-avc",
                audioUrl = "https://example.test/audio/233",
            ),
            YouTubeHlsMaster.select(withoutAudio234),
        )
    }

    @Test
    fun `returns null for a playlist without variants`() {
        assertNull(YouTubeHlsMaster.select("#EXTM3U\n"))
    }

    @Test
    fun `edl url length-quotes each part`() {
        val video = "https://v.test/a;b"
        val audio = "https://a.test/c,d"
        assertEquals(
            "edl://!no_clip;!no_chapters;%18%https://v.test/a;b;!new_stream;!no_clip;!no_chapters;%18%https://a.test/c,d",
            YouTubeHlsMaster.mpvEdlUrl(YouTubeHlsMaster.Selection(video, audio)),
        )
    }
}
