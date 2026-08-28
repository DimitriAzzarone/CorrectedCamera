package ch.formazione.correctedcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import ch.formazione.correctedcamera.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val streamServer = MjpegServer(8080)

    private val userRotation = AtomicInteger(0)
    private val latestCorrectedJpeg = AtomicReference<ByteArray?>(null)

    private var useFrontCamera = false
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

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
            if (activeRecording == null) {
                userRotation.set((userRotation.get() + 270) % 360)
                startCamera()
            }
        }

        binding.rotateRightButton.setOnClickListener {
            if (activeRecording == null) {
                userRotation.set((userRotation.get() + 90) % 360)
                startCamera()
            }
        }

        binding.switchCameraButton.setOnClickListener {
            if (activeRecording == null) {
                useFrontCamera = !useFrontCamera
                startCamera()
            }
        }

        binding.photoButton.setOnClickListener {
            saveCorrectedPhoto()
        }

        binding.videoButton.setOnClickListener {
            toggleVideoRecording()
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

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(
                            Quality.SD
                        )
                    )
                )
                .build()

            val capture = VideoCapture.withOutput(recorder)
            capture.targetRotation = rotationToSurface(userRotation.get())
            videoCapture = capture

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
                    analysis,
                    capture
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
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

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

            val cameraRotation = image.imageInfo.rotationDegrees
            val requestedRotation = userRotation.get()
            val finalRotation = (cameraRotation + requestedRotation) % 360

            val matrix = Matrix()

            if (finalRotation != 0) {
                matrix.postRotate(finalRotation.toFloat())
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
                binding.processedImageView.setImageBitmap(previewBitmap)
            }

            val jpeg =
                ByteArrayOutputStream().use { output ->

                    transformed.compress(
                        Bitmap.CompressFormat.JPEG,
                        92,
                        output
                    )

                    output.toByteArray()
                }

            latestCorrectedJpeg.set(jpeg)
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

    private fun saveCorrectedPhoto() {

        val jpeg = latestCorrectedJpeg.get()

        if (jpeg == null) {
            Toast.makeText(
                this,
                "Attendi che la fotocamera sia pronta",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {

            val name =
                "CorrectedCamera_${timestamp()}.jpg"

            val values =
                ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        name
                    )
                    put(
                        MediaStore.Images.Media.MIME_TYPE,
                        "image/jpeg"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/CorrectedCamera"
                        )
                        put(
                            MediaStore.Images.Media.IS_PENDING,
                            1
                        )
                    }
                }

            val uri =
                contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: error("Impossibile creare la foto")

            contentResolver
                .openOutputStream(uri)
                .use { output ->

                    requireNotNull(output)
                    output.write(jpeg)
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                values.clear()
                values.put(
                    MediaStore.Images.Media.IS_PENDING,
                    0
                )

                contentResolver.update(
                    uri,
                    values,
                    null,
                    null
                )
            }

            Toast.makeText(
                this,
                "Foto salvata in Pictures/CorrectedCamera",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Errore foto: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun toggleVideoRecording() {

        if (activeRecording != null) {

            activeRecording?.stop()
            return
        }

        val capture =
            videoCapture ?: run {

                Toast.makeText(
                    this,
                    "Videocamera non ancora pronta",
                    Toast.LENGTH_SHORT
                ).show()

                return
            }

        capture.targetRotation =
            rotationToSurface(
                userRotation.get()
            )

        val name =
            "CorrectedCamera_${timestamp()}.mp4"

        val values =
            ContentValues().apply {

                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    name
                )

                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "Movies/CorrectedCamera"
                    )
                }
            }

        val outputOptions =
            MediaStoreOutputOptions
                .Builder(
                    contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                )
                .setContentValues(values)
                .build()

        activeRecording =
            capture.output
                .prepareRecording(
                    this,
                    outputOptions
                )
                .start(
                    ContextCompat.getMainExecutor(this)
                ) { event ->

                    when (event) {

                        is VideoRecordEvent.Start -> {
                            setRecordingUi(true)
                        }

                        is VideoRecordEvent.Finalize -> {

                            activeRecording = null
                            setRecordingUi(false)

                            if (event.hasError()) {

                                Toast.makeText(
                                    this,
                                    "Errore video: ${event.error}",
                                    Toast.LENGTH_LONG
                                ).show()

                            } else {

                                Toast.makeText(
                                    this,
                                    "Video salvato in Movies/CorrectedCamera",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            updateStatus()
                        }
                    }
                }
    }

    private fun setRecordingUi(recording: Boolean) {

        binding.videoButton.text =
            if (recording) {
                "■ Ferma video"
            } else {
                "● Registra video"
            }

        binding.rotateLeftButton.isEnabled =
            !recording

        binding.rotateRightButton.isEnabled =
            !recording

        binding.switchCameraButton.isEnabled =
            !recording

        binding.photoButton.isEnabled =
            !recording

        updateStatus()
    }

    private fun rotationToSurface(
        degrees: Int
    ): Int {

        return when (
            ((degrees % 360) + 360) % 360
        ) {

            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
    }

    private fun timestamp(): String {

        return SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())
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

        val recording =
            if (activeRecording != null) {
                " · REC"
            } else {
                ""
            }

        binding.statusText.text =
            "Camera $camera · rotazione ${userRotation.get()}°$recording · http://$ip:8080/video"
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

        activeRecording?.stop()
        activeRecording = null

        streamServer.stop()
        cameraExecutor.shutdown()

        super.onDestroy()
    }
}
