package ch.formazione.correctedcamera

import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MjpegServer(private val port: Int = 8080) {
    private val running = AtomicBoolean(false)
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    fun updateFrame(jpeg: ByteArray) {
        latestJpeg.set(jpeg)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        acceptExecutor.execute {
            try {
                serverSocket = ServerSocket(port)
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    clientExecutor.execute { handleClient(socket) }
                }
            } catch (_: Exception) {
                // stop() chiude volontariamente il socket.
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val input = client.getInputStream().bufferedReader()
            val request = input.readLine() ?: return
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
            }

            val path = request.split(" ").getOrNull(1) ?: "/"
            val out = BufferedOutputStream(client.getOutputStream())

            if (path == "/video" || path == "/video.mjpg") {
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                     "Cache-Control: no-cache\r\n" +
                     "Pragma: no-cache\r\n" +
                     "Connection: close\r\n" +
                     "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                        .toByteArray()
                )
                out.flush()

                var lastIdentity: ByteArray? = null
                while (running.get() && !client.isClosed) {
                    val frame = latestJpeg.get()
                    if (frame != null && frame !== lastIdentity) {
                        lastIdentity = frame
                        val header =
                            "--frame\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${frame.size}\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(frame)
                        out.write("\r\n".toByteArray())
                        out.flush()
                    }
                    Thread.sleep(50)
                }
            } else {
                val html = """
                    <!doctype html>
                    <html>
                    <head>
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>Corrected Camera</title>
                    </head>
                    <body style="margin:0;background:#111;color:#eee;font-family:sans-serif">
                      <div style="padding:12px">Corrected Camera — stream MJPEG</div>
                      <img src="/video" style="width:100%;height:auto;display:block">
                    </body>
                    </html>
                """.trimIndent().toByteArray()

                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                     "Content-Type: text/html; charset=utf-8\r\n" +
                     "Content-Length: ${html.size}\r\n" +
                     "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(html)
                out.flush()
            }
        }
    }
}
