package ch.formazione.correctedcamera

import java.nio.ByteBuffer

/** Copy only real pixels: the final row need not contain its padding bytes. */
object FrameBuffer {
    fun argb(rgba: ByteArray): IntArray {
        require(rgba.size % 4 == 0)
        return IntArray(rgba.size / 4) { pixel ->
            val i = pixel * 4
            ((rgba[i+3].toInt() and 255) shl 24) or
                ((rgba[i].toInt() and 255) shl 16) or
                ((rgba[i+1].toInt() and 255) shl 8) or (rgba[i+2].toInt() and 255)
        }
    }

    fun rgba(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000)
        require(pixelStride >= 4 && rowStride.toLong() >= (width - 1L) * pixelStride + 4)
        val source = buffer.duplicate()
        val base = source.position()
        val end = base.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride + 4
        require(end <= source.limit()) { "Buffer RGBA incompleto" }
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            val row = base + y * rowStride
            if (pixelStride == 4) {
                source.position(row)
                source.get(pixels, y * width * 4, width * 4)
            } else for (x in 0 until width) {
                val from = row + x * pixelStride
                val to = (y * width + x) * 4
                for (channel in 0..3) pixels[to + channel] = source.get(from + channel)
            }
        }
        return pixels
    }
}

/** At most one frame waits for the UI, regardless of camera speed. */
class LatestFrameSlot<T> {
    private var value: T? = null
    @Synchronized fun offer(next: T): T? { val old = value; value = next; return old }
    @Synchronized fun take(): T? { val old = value; value = null; return old }
}

class FramePacer(private val intervalMs: Long = 67) {
    private var last = Long.MIN_VALUE
    fun accept(now: Long): Boolean {
        if (last != Long.MIN_VALUE && now >= last && now - last < intervalMs) return false
        last = now
        return true
    }
}
