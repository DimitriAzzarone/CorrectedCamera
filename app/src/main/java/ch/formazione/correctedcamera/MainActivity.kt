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

    private val userRotation = AtomicInteger(0)

    private var useFrontCamera = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.statusText.text = "Permesso fotocamera negato"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamServer.start()

        binding.rotateLeftButton.setOnClickListener {
            userRotation.set((userRotation.get() + 270) % 360)
            updateStatus()
        }

        binding.rotateRightButton.setOnClickListener {
            userRotation.set((userRotation.get() + 90) % 360)
            updateStatus()
        }

        binding.switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {

        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            val provider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .setOutputImageFormat(
                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                )
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                processFrame(image)
            }

            val selector =
                if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

            try {

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    selector,
                    analysis
                )

                updateStatus()

            } catch (e: Exception) {

                binding.statusText.text =
                    "Errore camera: ${e.message}"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {

        var cropped: Bitmap? = null
        var transformed: Bitmap? = null

        try {

            val width = image.width
            val height = image.height

            val plane = image.planes[0]

            val buffer = plane.buffer

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val rowPadding =
                rowStride - pixelStride * width

            val paddedWidth =
                width + rowPadding / pixelStride

            val paddedBitmap =
                Bitmap.createBitmap(
                    paddedWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )

            buffer.rewind()

            paddedBitmap.copyPixelsFromBuffer(buffer)

            cropped =
                Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    width,
                    height
                )

            paddedBitmap.recycle()

            val cameraRotation =
                image.imageInfo.rotationDegrees

            val requestedRotation =
                userRotation.get()

            val finalRotation =
                (cameraRotation + requestedRotation) % 360

            val matrix = Matrix()

            if (finalRotation != 0) {
                matrix.postRotate(
                    finalRotation.toFloat()
                )
            }

            transformed =
                if (finalRotation == 0) {

                    cropped.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                    )

                } else {

                    Bitmap.createBitmap(
                        cropped,
                        0,
                        0,
                        cropped.width,
                        cropped.height,
                        matrix,
                        true
                    )
                }

            val previewBitmap =
                transformed.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            runOnUiThread {

                binding.processedImageView.setImageBitmap(
                    previewBitmap
                )
            }

            val jpeg =
                ByteArrayOutputStream().use { output ->

                    transformed.compress(
                        Bitmap.CompressFormat.JPEG,
                        75,
                        output
                    )

                    output.toByteArray()
                }

            streamServer.updateFrame(jpeg)

        } catch (e: Exception) {

            runOnUiThread {

                binding.statusText.text =
                    "Errore frame: ${e.message}"
            }

        } finally {

            transformed?.recycle()
            cropped?.recycle()

            image.close()
        }
    }

    private fun updateStatus() {

        val ip =
            localIpv4() ?: "IP-del-tablet"

        val camera =
            if (useFrontCamera) {
                "frontale"
            } else {
                "posteriore"
            }

        binding.statusText.text =
            "Camera $camera · rotazione ${userRotation.get()}° · http://$ip:8080/video"
    }

    private fun localIpv4(): String? {

        return try {

            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .flatMap {
                    it.inetAddresses.toList()
                }
                .firstOrNull {
                    !it.isLoopbackAddress &&
                    it is Inet4Address
                }
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
