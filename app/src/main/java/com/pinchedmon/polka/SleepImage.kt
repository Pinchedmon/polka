package com.pinchedmon.polka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns any picture into what CrossPoint wants for a sleep screen: 480×800, 24-bit uncompressed BMP. */
object SleepImage {
    const val W = 480
    const val H = 800

    fun load(context: Context, uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Don't decode a 50 MP photo at full size just to shrink it to 480×800.
                val scale = maxOf(1, minOf(info.size.width / W, info.size.height / H) / 2)
                d.setTargetSize(info.size.width / scale, info.size.height / scale)
            }
        } else {
            @Suppress("DEPRECATION")
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        }
    }

    /** [crop] fills the screen and cuts the edges; otherwise the whole picture fits with white margins. */
    fun render(src: Bitmap, crop: Boolean): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val scale = if (crop) maxOf(W / sw, H / sh) else minOf(W / sw, H / sh)
        val dw = (sw * scale).toInt()
        val dh = (sh * scale).toInt()
        val left = (W - dw) / 2
        val top = (H - dh) / 2
        // E-ink is grayscale anyway — convert here so the preview shows what the reader will show.
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        c.drawBitmap(src, null, Rect(left, top, left + dw, top + dh), paint)
        return out
    }

    fun toBmp(b: Bitmap): ByteArray {
        val rowSize = W * 3 // 1440, already a multiple of 4 — no row padding
        val dataSize = rowSize * H
        val buf = ByteBuffer.allocate(54 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte())
        buf.putInt(54 + dataSize).putInt(0).putInt(54)
        buf.putInt(40).putInt(W).putInt(H).putShort(1).putShort(24)
        buf.putInt(0).putInt(dataSize).putInt(2835).putInt(2835).putInt(0).putInt(0)
        val px = IntArray(W)
        for (y in H - 1 downTo 0) { // BMP rows go bottom-up
            b.getPixels(px, 0, W, 0, y, W, 1)
            for (p in px) {
                buf.put((p and 0xff).toByte())
                buf.put((p shr 8 and 0xff).toByte())
                buf.put((p shr 16 and 0xff).toByte())
            }
        }
        return buf.array()
    }

    fun thumb(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
}
