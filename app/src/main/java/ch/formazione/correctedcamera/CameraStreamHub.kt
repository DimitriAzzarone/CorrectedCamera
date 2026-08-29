package ch.formazione.correctedcamera

object CameraStreamHub {
    private val server = MjpegServer(8080)
    @Volatile private var started = false

    @Synchronized
    fun start() {
        if (!started) {
            server.start()
            started = true
        }
    }

    fun updateFrame(jpeg: ByteArray) {
        start()
        server.updateFrame(jpeg)
    }
}
