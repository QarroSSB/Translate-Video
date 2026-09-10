package com.qarro.livetranslator

import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeExtractorNetworkTest {
    @Test
    fun resolvesStablePublicYoutubeVideo() {
        val resolver = YouTubeStreamResolver()
        try {
            val stream = resolver.resolveBlockingForDiagnostics(
                "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            )
            println(
                "QARRO_YT_DIAG success title=${stream.title.take(80)} variants=${stream.variants.size} " +
                    "first=${stream.variants.firstOrNull()?.label.orEmpty()} net=${QarroDownloader.lastDiagnostic}",
            )
            assertTrue("Expected at least one playable YouTube variant", stream.variants.isNotEmpty())
        } catch (t: Throwable) {
            println(
                "QARRO_YT_DIAG failure type=${t.javaClass.name} message=${t.message} " +
                    "net=${QarroDownloader.lastDiagnostic}",
            )
            throw t
        } finally {
            resolver.close()
        }
    }
}
