package ch.formazione.correctedcamera

object CameraStreamHub {
    private val server = MjpegServer(8080)
    private var started = false
    private var serial = 0L
    private var source = 0L
    @Volatile var message = "Camera ferma"; private set
    @Volatile var lastFrameAt = 0L; private set

    @Synchronized fun start() { if (!started) { server.start(); started = true } }
    @Synchronized fun beginSource(): Long {
        start(); source = ++serial; lastFrameAt = 0; message = "Avvio fotocamera…"; server.clearFrame(); return source
    }
    @Synchronized fun updateFrame(owner: Long, jpeg: ByteArray) {
        if (owner != source || owner == 0L) return
        lastFrameAt = android.os.SystemClock.elapsedRealtime(); message = "Video attivo"; server.updateFrame(jpeg)
    }
    @Synchronized fun status(owner: Long, text: String) { if (owner == source && owner != 0L) message = text }
    @Synchronized fun endSource(owner: Long) {
        if (owner == source && owner != 0L) { source = 0; lastFrameAt = 0; message = "Camera ferma"; server.clearFrame() }
    }
}
