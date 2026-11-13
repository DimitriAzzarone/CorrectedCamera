package ch.formazione.correctedcamera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object BitmapFrames {
    fun corrected(image: ImageProxy, requestedRotation: Int): Bitmap {
        val plane = image.planes[0]
        return fromRgba(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride, image.imageInfo.rotationDegrees + requestedRotation)
    }
    fun fromRgba(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, rotation: Int): Bitmap {
        val bytes = FrameBuffer.rgba(buffer, width, height, rowStride, pixelStride)
        // CameraX specifies R,G,B,A bytes; ARGB ints avoid native Skia byte-order assumptions.
        val raw = Bitmap.createBitmap(FrameBuffer.argb(bytes), width, height, Bitmap.Config.ARGB_8888)
        val degrees = ((rotation % 360) + 360) % 360
        if (degrees == 0) return raw
        return try {
            val result = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            if (result !== raw) raw.recycle()
            result
        } catch (e: Exception) { raw.recycle(); throw e }
    }
    fun scaled(source: Bitmap, maxSide: Int, copy: Boolean = false): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
        if (scale == 1f) return if (copy) source.copy(Bitmap.Config.ARGB_8888, false) else source
        return Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), true)
    }
}

class BitmapPresenter(private val target: () -> ImageView?) {
    private val handler = Handler(Looper.getMainLooper())
    private val slot = LatestFrameSlot<Bitmap>()
    private val lock = Any()
    private var closed = false
    private var scheduled = false
    private val display = Runnable {
        val frame = synchronized(lock) { scheduled = false; slot.take() }
        if (frame != null) {
            val view = if (closed) null else target()
            if (view == null) frame.recycle() else view.setImageBitmap(frame)
            // Never recycle a displayed bitmap: RenderThread may still use it.
        }
    }
    fun offer(frame: Bitmap) {
        synchronized(lock) {
            if (closed) { frame.recycle(); return }
            slot.offer(frame)?.recycle() // Discarded frame was never drawn.
            if (!scheduled) { scheduled = true; handler.post(display) }
        }
    }
    fun clearPending() { synchronized(lock) { slot.take()?.recycle() } }
    fun close() {
        synchronized(lock) { closed = true; handler.removeCallbacks(display); slot.take()?.recycle(); scheduled = false }
    }
}
