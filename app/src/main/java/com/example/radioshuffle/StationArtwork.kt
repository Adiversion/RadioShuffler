package com.example.radioshuffle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * Provides rasterized station artwork and resource Uris for Android SystemUI,
 * lock screen media widgets, and Xiaomi HyperOS Super Island / Live Updates.
 */
object StationArtwork {
    @Volatile
    private var cachedArtwork: ByteArray? = null

    fun getArtworkUri(context: Context): Uri {
        return Uri.parse("android.resource://${context.packageName}/${R.drawable.ic_radio_notification}")
    }

    fun getArtworkData(context: Context): ByteArray? {
        cachedArtwork?.let { return it }
        return synchronized(this) {
            cachedArtwork?.let { return it }
            try {
                val size = 256
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                // Sleek circular dark background
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#141922")
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

                // Emerald Green accent border ring (#00E676)
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#00E676")
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 3f, ringPaint)

                // Draw radio icon in center
                val drawable = ContextCompat.getDrawable(context, R.drawable.ic_radio_notification)
                if (drawable != null) {
                    val pad = 54
                    drawable.setBounds(pad, pad, size - pad, size - pad)
                    drawable.setTint(android.graphics.Color.parseColor("#00E676"))
                    drawable.draw(canvas)
                }

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val bytes = stream.toByteArray()
                cachedArtwork = bytes
                bytes
            } catch (_: Exception) {
                null
            }
        }
    }
}
