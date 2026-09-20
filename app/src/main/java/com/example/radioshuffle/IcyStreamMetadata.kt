package com.example.radioshuffle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Lightweight in-stream probe for internet radio stations emitting Shoutcast/Icecast ICY metadata.
 * Mirrors the protocol verified in check_song.py by reading up to the first metadata boundary
 * and cleanly terminating the connection to preserve battery and network bandwidth.
 */
object IcyStreamMetadata {
    private const val TAG = "IcyStreamMetadata"
    private val TITLE_PATTERN = Pattern.compile("StreamTitle='([^']*)'")

    suspend fun probeTrackTitle(streamUrl: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        try {
            val url = URL(streamUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                )
                setRequestProperty("Icy-MetaData", "1")
                setRequestProperty("Referer", "https://radio.garden/")
                setRequestProperty("Origin", "https://radio.garden")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..399) {
                return@withContext null
            }

            val metaIntStr = connection.getHeaderField("icy-metaint") ?: return@withContext null
            val metaInt = metaIntStr.toIntOrNull() ?: return@withContext null
            if (metaInt <= 0) return@withContext null

            inputStream = connection.inputStream

            // Read audio stream bytes up to the first metadata boundary
            var skipped = 0L
            while (skipped < metaInt) {
                val n = inputStream.skip(metaInt - skipped)
                if (n <= 0) {
                    if (inputStream.read() == -1) return@withContext null
                    skipped += 1
                } else {
                    skipped += n
                }
            }

            // Read 1-byte length prefix (length in 16-byte blocks)
            val lenByte = inputStream.read()
            if (lenByte <= 0) return@withContext null

            val metaLen = lenByte * 16
            val buffer = ByteArray(metaLen)
            var totalRead = 0
            while (totalRead < metaLen) {
                val r = inputStream.read(buffer, totalRead, metaLen - totalRead)
                if (r == -1) break
                totalRead += r
            }

            if (totalRead > 0) {
                val metaChunk = String(buffer, 0, totalRead, Charsets.UTF_8)
                val matcher = TITLE_PATTERN.matcher(metaChunk)
                if (matcher.find()) {
                    val candidate = matcher.group(1)?.trim()
                    if (!candidate.isNullOrBlank()) {
                        return@withContext candidate
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Probe ended: ${e.message}")
            null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
