package ch.formazione.correctedcamera

import android.Manifest
import android.app.PictureInPictureParams
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Outline
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.view.ViewOutlineProvider
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.Surface
import android.view.Gravity
import android.view.View
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

    private val userRotation = AtomicInteger(0)
    private val latestCorrectedJpeg = AtomicReference<ByteArray?>(null)

    private var useFrontCamera = false
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lastMainStreamFrameAt = 0L

    private var pipCircular = true
    private var pipSizeMode = 1 // 0=piccola, 1=media, 2=grande

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data

            if (result.resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }

                ContextCompat.startForegroundService(this, serviceIntent)
                binding.screenVideoButton.text = "■ Ferma registrazione schermo"
                requestOrStartFloatingOverlay()
            } else {
                Toast.makeText(
                    this,
                    "Registrazione schermo annullata",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingOverlay()
            } else {
                Toast.makeText(
                    this,
                    "Serve il permesso 'Mostra sopra altre app'",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.statusText.text = "Permesso fotocamera negato"
            }
        }

    override fun onResume() {
        super.onResume()

        if (FloatingCameraService.isRunning) {
            binding.statusText.text = "Trasmissione in background attiva"
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CameraStreamHub.start()
        configurePictureInPicture()

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
                switchCameraSafely()
            }
        }

        binding.photoButton.setOnClickListener {
            saveCorrectedPhoto()
        }

        binding.videoButton.setOnClickListener {
            toggleVideoRecording()
        }

        binding.screenVideoButton.setOnClickListener {
            toggleScreenRecording()
        }

        binding.pipShapeButton.setOnClickListener {
            pipCircular = !pipCircular
            updatePipChoiceButtons()
        }

        binding.pipSizeButton.setOnClickListener {
            pipSizeMode = (pipSizeMode + 1) % 3
            updatePipChoiceButtons()
        }

        binding.alwaysOnTopButton.setOnClickListener {
            requestOrStartFloatingOverlay()
        }

        binding.hideOverlayButton.setOnClickListener {
            startBackgroundCameraService(showOverlay = false)
            moveTaskToBack(true)
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


    private fun updatePipChoiceButtons() {
        binding.pipShapeButton.text =
            if (pipCircular) "Forma: tondo" else "Forma: quadrato"

        binding.pipSizeButton.text =
            when (pipSizeMode) {
                0 -> "Dimensione: piccola"
                1 -> "Dimensione: media"
                else -> "Dimensione: grande"
            }
    }

    private fun hideWindowTaskTitle() {
        title = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(
                ActivityManager.TaskDescription("")
            )
        }
    }

    private fun restoreWindowTaskTitle() {
        title = "Corrected Camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(
                ActivityManager.TaskDescription("Corrected Camera")
            )
        }
    }

    private fun configurePictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ratio = if (pipCircular) Rational(1, 1) else Rational(4, 3)

            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.setTitle("")
                builder.setSubtitle("")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(false)
                builder.setSeamlessResizeEnabled(true)
            }

            setPictureInPictureParams(builder.build())
        }
    }

    private fun enterCorrectedCameraPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                hideWindowTaskTitle()
                val ratio = if (pipCircular) Rational(1, 1) else Rational(4, 3)

                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.setTitle("")
                    builder.setSubtitle("")
                }

                enterPictureInPictureMode(
                    builder.build()
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Impossibile aprire la finestra mobile: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(
            isInPictureInPictureMode,
            newConfig
        )

        val visibility =
            if (isInPictureInPictureMode) View.GONE else View.VISIBLE

        binding.statusText.visibility = visibility
        binding.controlsContainer.visibility = visibility

        if (isInPictureInPictureMode) {
            hideWindowTaskTitle()
        } else {
            restoreWindowTaskTitle()
        }

        applyPipShape(isInPictureInPictureMode)
    }

    private fun requestOrStartFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        startFloatingOverlay()
    }

    private fun startFloatingOverlay() {
        startBackgroundCameraService(showOverlay = true)
        moveTaskToBack(true)
    }

    private fun startBackgroundCameraService(showOverlay: Boolean) {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                providerFuture.get().unbindAll()
            } catch (_: Exception) {
            }

            val intent = Intent(this, FloatingCameraService::class.java).apply {
                putExtra(FloatingCameraService.EXTRA_FRONT, useFrontCamera)
                putExtra(FloatingCameraService.EXTRA_ROTATION, userRotation.get())
                putExtra(FloatingCameraService.EXTRA_CIRCULAR, pipCircular)
                putExtra(FloatingCameraService.EXTRA_SIZE_MODE, pipSizeMode)
                putExtra(FloatingCameraService.EXTRA_SHOW_OVERLAY, showOverlay)
            }

            ContextCompat.startForegroundService(this, intent)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleScreenRecording() {
        if (ScreenCaptureService.isRecording) {
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            binding.screenVideoButton.text = "⏺ Registra schermo completo"
            return
        }

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        screenCaptureLauncher.launch(
            manager.createScreenCaptureIntent()
        )
    }

    private fun applyPipShape(inPip: Boolean) {
        if (inPip) {
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
            binding.rootContainer.setBackgroundColor(Color.TRANSPARENT)
            binding.rootContainer.setPadding(0, 0, 0, 0)
            binding.previewFrame.setPadding(0, 0, 0, 0)
            binding.previewFrame.background = null

            val params = binding.processedImageView.layoutParams as android.widget.FrameLayout.LayoutParams

            when (pipSizeMode) {
                0 -> {
                    val size = dp(130)
                    params.width = size
                    params.height = size
                }
                1 -> {
                    val size = dp(185)
                    params.width = size
                    params.height = size
                }
                else -> {
                    params.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            params.gravity = Gravity.CENTER
            binding.processedImageView.layoutParams = params
            binding.processedImageView.scaleType =
                android.widget.ImageView.ScaleType.CENTER_CROP

            if (pipCircular) {
                binding.processedImageView.outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(
                            view: android.view.View,
                            outline: Outline
                        ) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                binding.processedImageView.clipToOutline = true
            } else {
                binding.processedImageView.clipToOutline = false
                binding.processedImageView.outlineProvider = null
            }
        } else {
            window.decorView.setBackgroundColor(Color.rgb(16, 20, 24))
            binding.rootContainer.setBackgroundColor(Color.rgb(16, 20, 24))

            val p = dp(14)
            binding.rootContainer.setPadding(p, p, p, p)

            val previewPadding = dp(6)
            binding.previewFrame.setPadding(
                previewPadding, previewPadding, previewPadding, previewPadding
            )
            binding.previewFrame.setBackgroundResource(
                ch.formazione.correctedcamera.R.drawable.panel_preview
            )

            val params = binding.processedImageView.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
            binding.processedImageView.layoutParams = params

            binding.processedImageView.clipToOutline = false
            binding.processedImageView.outlineProvider = null
            binding.processedImageView.scaleType =
                android.widget.ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun switchCameraSafely() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()
            val requestedFront = !useFrontCamera
            val requestedSelector =
                if (requestedFront) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

            if (!provider.hasCamera(requestedSelector)) {
                Toast.makeText(
                    this,
                    if (requestedFront) {
                        "Fotocamera frontale non disponibile"
                    } else {
                        "Fotocamera posteriore non disponibile"
                    },
                    Toast.LENGTH_LONG
                ).show()
                return@addListener
            }

            useFrontCamera = requestedFront
            startCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()

            val selector =
                if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

            if (!provider.hasCamera(selector)) {
                val fallbackSelector =
                    if (useFrontCamera) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }

                if (provider.hasCamera(fallbackSelector)) {
                    useFrontCamera = !useFrontCamera
                    Toast.makeText(
                        this,
                        "Camera richiesta non disponibile: uso l'altra fotocamera",
                        Toast.LENGTH_LONG
                    ).show()
                    startCamera()
                } else {
                    binding.statusText.text = "Nessuna fotocamera disponibile"
                }
                return@addListener
            }

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

            try {
                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    selector,
                    analysis,
                    capture
                )

                videoCapture = capture
                updateStatus()

            } catch (fullBindError: Exception) {
                // Alcuni dispositivi non permettono ImageAnalysis + VideoCapture
                // contemporaneamente sulla camera frontale. In quel caso
                // manteniamo preview/stream/foto e disabilitiamo solo Video camera.
                try {
                    provider.unbindAll()

                    provider.bindToLifecycle(
                        this,
                        selector,
                        analysis
                    )

                    videoCapture = null
                    binding.videoButton.isEnabled = false

                    binding.statusText.text =
                        if (useFrontCamera) {
                            "Camera frontale attiva · video camera non supportato su questo dispositivo"
                        } else {
                            "Camera posteriore attiva · video camera non supportato su questo dispositivo"
                        }

                } catch (analysisOnlyError: Exception) {
                    binding.videoButton.isEnabled = true
                    binding.statusText.text =
                        "Errore camera: ${analysisOnlyError.message}"
                }
            }

            if (videoCapture != null) {
                binding.videoButton.isEnabled = true
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

            val now = android.os.SystemClock.elapsedRealtime()

            if (now - lastMainStreamFrameAt >= 50L) {
                lastMainStreamFrameAt = now

                val streamBitmap =
                    if (maxOf(transformed.width, transformed.height) > 480) {
                        val scale =
                            480f / maxOf(
                                transformed.width,
                                transformed.height
                            ).toFloat()

                        Bitmap.createScaledBitmap(
                            transformed,
                            maxOf(1, (transformed.width * scale).toInt()),
                            maxOf(1, (transformed.height * scale).toInt()),
                            true
                        )
                    } else {
                        transformed
                    }

                val jpeg =
                    ByteArrayOutputStream().use { output ->
                        streamBitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            55,
                            output
                        )
                        output.toByteArray()
                    }

                if (streamBitmap !== transformed) {
                    streamBitmap.recycle()
                }

                latestCorrectedJpeg.set(jpeg)
                CameraStreamHub.updateFrame(jpeg)
            }

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

        cameraExecutor.shutdown()

        super.onDestroy()
    }
}
