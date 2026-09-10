package com.qarro.livetranslator

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight Media3 transport for YouTube playback URLs.
 *
 * Modern googlevideo /videoplayback endpoints are more reliable when requested like the
 * NewPipe player does: POST with a tiny body, YouTube web headers, an rn request number and,
 * for DASH byte-range requests, the range query parameter instead of a Range header.
 */
class YoutubeMediaDataSource private constructor(
    private val rangeParameterEnabled: Boolean,
    private val rnParameterEnabled: Boolean,
) : DataSource {
    class Factory(
        private val rangeParameterEnabled: Boolean,
        private val rnParameterEnabled: Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return YoutubeMediaDataSource(rangeParameterEnabled, rnParameterEnabled)
        }
    }

    companion object {
        private val requestNumber = AtomicLong(0)
        private val POST_BODY = byteArrayOf(0x78, 0x00)
    }

    private val delegate = DefaultHttpDataSource.Factory()
        .setUserAgent(QarroDownloader.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)
        .createDataSource()

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val transformed = transform(dataSpec)
        return delegate.open(transformed)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        delegate.close()
    }

    private fun transform(original: DataSpec): DataSpec {
        val originalUrl = original.uri.toString()
        val isVideoPlayback = original.uri.path?.startsWith("/videoplayback") == true
        if (!isVideoPlayback) return original

        var url = originalUrl
        if (rnParameterEnabled && !Regex("[?&]rn=").containsMatchIn(url)) {
            url = appendRawQuery(url, "rn=${requestNumber.getAndIncrement()}")
        }

        var position = original.position
        val requestedLength = original.length
        if (rangeParameterEnabled && original.position >= 0) {
            val end = if (original.length != C.LENGTH_UNSET.toLong() && original.length > 0) {
                original.position + original.length - 1
            } else {
                ""
            }
            url = appendRawQuery(url, "range=${original.position}-$end")
            // The server response is already the requested range. Prevent DefaultHttpDataSource
            // from adding a second Range header / skipping original.position bytes again.
            position = 0
        }

        val headers = LinkedHashMap(original.httpRequestHeaders)
        headers["Origin"] = "https://www.youtube.com"
        headers["Referer"] = "https://www.youtube.com/"
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Site"] = "cross-site"
        headers["TE"] = "trailers"
        headers["Accept-Encoding"] = "identity"
        headers["User-Agent"] = QarroDownloader.USER_AGENT

        return original.buildUpon()
            .setUri(Uri.parse(url))
            .setPosition(position)
            .setLength(requestedLength)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(POST_BODY)
            .setHttpRequestHeaders(headers)
            .build()
    }

    private fun appendRawQuery(url: String, parameter: String): String {
        return if (url.contains('?')) "$url&$parameter" else "$url?$parameter"
    }
}
