package ch.formazione.correctedcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ch.formazione.correctedcamera.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val streamServer = MjpegServer(8080)
    private val rotationDegrees = AtomicInteger(0)
    private var useFrontCamera = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else binding.statusText.text = "Permesso fotocamera negato"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamServer.start()

        binding.rotateLeftButton.setOnClickListener {
            rotationDegrees.set((rotationDegrees.get() + 270) % 360)
            updatePreviewRotation()
            updateStatus()
        }

        binding.rotateRightButton.setOnClickListener {
            rotationDegrees.set((rotationDegrees.get() + 90) % 360)
            updatePreviewRotation()
            updateStatus()
        }

        binding.switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                processFrame(image)
            }

            val selector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                updateStatus()
            } catch (e: Exception) {
                binding.statusText.text = "Errore camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val paddedWidth = width + rowPadding / pixelStride
            val paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                height,
                Bitmap.Config.ARGB_8888
            )

            buffer.rewind()
            paddedBitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            if (cropped !== paddedBitmap) paddedBitmap.recycle()

            val angle = ((image.imageInfo.rotationDegrees + rotationDegrees.get()) % 360).toFloat()
            val matrix = Matrix().apply {
                if (angle != 0f) postRotate(angle)
                // La preview frontale di molte app è specchiata; lo stream resta non specchiato
                // finché non aggiungiamo un controllo esplicito.
            }

            val transformed =
                if (angle == 0f) cropped
                else Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)

            val jpeg = ByteArrayOutputStream().use { output ->
                transformed.compress(Bitmap.CompressFormat.JPEG, 75, output)
                output.toByteArray()
            }

            streamServer.updateFrame(jpeg)

            if (transformed !== cropped) transformed.recycle()
            cropped.recycle()
        } catch (_: Exception) {
            // In un prototipo di streaming preferiamo scartare un frame difettoso.
        } finally {
            image.close()
        }
    }

    private fun updatePreviewRotation() {
        binding.previewView.animate()
            .rotation(rotationDegrees.get().toFloat())
            .setDuration(180)
            .start()
    }

    private fun updateStatus() {
        val ip = localIpv4() ?: "IP-del-tablet"
        val camera = if (useFrontCamera) "frontale" else "posteriore"
        binding.statusText.text =
            "Camera $camera · rotazione ${rotationDegrees.get()}° · http://$ip:8080/video"
    }

    private fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamServer.stop()
        cameraExecutor.shutdown()
    }
}
