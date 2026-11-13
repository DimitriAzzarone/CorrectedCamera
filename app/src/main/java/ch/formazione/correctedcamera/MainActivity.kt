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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.Surface
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraState
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
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

    private var cameraProvider: ProcessCameraProvider? = null
    private val ownedUseCases = mutableListOf<UseCase>()
    @Volatile private var cameraGeneration = 0
    @Volatile private var handoffPending = false
    @Volatile private var destroyed = false
    @Volatile private var sourceToken = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val presenter = BitmapPresenter { if (destroyed) null else binding.processedImageView }
    private val pacer = FramePacer()
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
        handler.removeCallbacks(cameraHealthUi)
        handler.postDelayed(cameraHealthUi, 1000)

        if (FloatingCameraService.isRunning || handoffPending) {
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
                applyCameraSettings()
            }
        }

        binding.rotateRightButton.setOnClickListener {
            if (activeRecording == null) {
                userRotation.set((userRotation.get() + 90) % 360)
                applyCameraSettings()
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
            if (FloatingCameraService.isRunning) applyCameraSettings()
        }

        binding.pipSizeButton.setOnClickListener {
            pipSizeMode = (pipSizeMode + 1) % 3
            updatePipChoiceButtons()
            if (FloatingCameraService.isRunning) applyCameraSettings()
        }

        binding.alwaysOnTopButton.setOnClickListener {
            requestOrStartFloatingOverlay()
        }

        binding.hideOverlayButton.setOnClickListener {
            startBackgroundCameraService(showOverlay = false, minimizeAfterReady = true)
        }

        binding.stopOverlayButton.setOnClickListener {
            if (activeRecording != null) {
                Toast.makeText(this, "Ferma prima la registrazione video", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            handoffPending = false; cameraGeneration++
            stopService(Intent(this, FloatingCameraService::class.java))
            releaseOwnCamera()
            binding.statusText.text = "Camera flottante fermata · puoi tornare alla chiamata"
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permissionLauncher.launch(Manifest.permission.CAMERA)
        // Initial binding happens in onResume, not twice in onCreate/onResume.
    }


    private var showingCameraWarning = false
    private val cameraHealthUi = object : Runnable {
        override fun run() {
            if (destroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
            if (!handoffPending && (sourceToken != 0L || FloatingCameraService.isRunning)) {
                val age = android.os.SystemClock.elapsedRealtime() - CameraStreamHub.lastFrameAt
                val message = if (CameraStreamHub.lastFrameAt > 0 && age > 3000 && CameraStreamHub.message == "Video attivo")
                    "Video fermo · possibile uso della camera da un'altra app" else CameraStreamHub.message
                if (message != "Video attivo") { binding.statusText.text = message; showingCameraWarning = true }
                else if (showingCameraWarning) { showingCameraWarning = false; updateStatus() }
            }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onPause() { handler.removeCallbacks(cameraHealthUi); super.onPause() }

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
        title = "Corrected Camera Test"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(
                ActivityManager.TaskDescription("Corrected Camera Test")
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
        startBackgroundCameraService(showOverlay = true, minimizeAfterReady = true)
    }

    private fun applyCameraSettings() {
        if (FloatingCameraService.isRunning) startBackgroundCameraService(FloatingCameraService.overlayVisible)
        else updateStatus() // Rotation is an image transform; it need not reopen the camera.
    }

    private fun releaseOwnCamera() {
        cameraGeneration++
        ownedUseCases.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
        if (ownedUseCases.isNotEmpty()) cameraProvider?.unbind(*ownedUseCases.toTypedArray())
        ownedUseCases.clear(); videoCapture = null
        presenter.clearPending()
        CameraStreamHub.endSource(sourceToken); sourceToken = 0
    }

    private fun startBackgroundCameraService(showOverlay: Boolean, minimizeAfterReady: Boolean = false) {
        if (handoffPending) return
        if (activeRecording != null) {
            Toast.makeText(this, "Ferma prima la registrazione della camera", Toast.LENGTH_LONG).show(); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            binding.statusText.text = "Concedi prima il permesso fotocamera"; return
        }
        handoffPending = true
        cameraGeneration++ // Invalidate any late Activity provider callback.
        binding.root.post {
            if (destroyed || !handoffPending) return@post
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                handoffPending = false; binding.statusText.text = "Torna nell'app per avviare la camera flottante"; return@post
            }
            try {
                releaseOwnCamera()
                val intent = Intent(this, FloatingCameraService::class.java).apply {
                    putExtra(FloatingCameraService.EXTRA_FRONT, useFrontCamera)
                    putExtra(FloatingCameraService.EXTRA_ROTATION, userRotation.get())
                    putExtra(FloatingCameraService.EXTRA_CIRCULAR, pipCircular)
                    putExtra(FloatingCameraService.EXTRA_SIZE_MODE, pipSizeMode)
                    putExtra(FloatingCameraService.EXTRA_SHOW_OVERLAY, showOverlay)
                }
                ContextCompat.startForegroundService(this, intent)
                binding.statusText.text = "Avvio camera flottante…"
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                fun awaitReady() {
                    if (destroyed || !handoffPending) return
                    val ready = FloatingCameraService.isRunning && CameraStreamHub.lastFrameAt > 0 &&
                            android.os.SystemClock.elapsedRealtime() - CameraStreamHub.lastFrameAt < 1500
                    if (ready) {
                        handoffPending = false
                        binding.statusText.text = "Camera flottante attiva"
                        if (minimizeAfterReady && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) moveTaskToBack(true)
                    } else if (android.os.SystemClock.elapsedRealtime() > deadline) {
                        handoffPending = false
                        binding.statusText.text = "Camera non pronta: ${CameraStreamHub.message}"
                    } else handler.postDelayed({ awaitReady() }, 200)
                }
                handler.postDelayed({ awaitReady() }, 200)
            } catch (e: Exception) {
                handoffPending = false
                binding.statusText.text = "Avvio camera non riuscito: ${e.message}"
            }
        }
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
            if (FloatingCameraService.isRunning) applyCameraSettings() else { releaseOwnCamera(); startCamera() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCamera() {
        if (destroyed || handoffPending || FloatingCameraService.isRunning) return
        if (ownedUseCases.isNotEmpty()) return // CameraX resumes this lifecycle itself.
        val generation = ++cameraGeneration
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            if (destroyed || handoffPending || FloatingCameraService.isRunning || generation != cameraGeneration) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider
            sourceToken = CameraStreamHub.beginSource()

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
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640,480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .setOutputImageFormat(
                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                )
                .build()

            val frameSource = sourceToken
            analysis.setAnalyzer(cameraExecutor) { image ->
                if (destroyed || handoffPending || generation != cameraGeneration) image.close() else processFrame(image, frameSource, generation)
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
                if (ownedUseCases.isNotEmpty()) provider.unbind(*ownedUseCases.toTypedArray())
                ownedUseCases.clear()
                val camera = provider.bindToLifecycle(
                    this,
                    selector,
                    analysis,
                    capture
                )

                ownedUseCases.addAll(listOf(analysis, capture))
                camera.cameraInfo.cameraState.observe(this) { state ->
                    if (generation != cameraGeneration) return@observe
                    state.error?.let { error ->
                        binding.statusText.text = when(error.code) {
                            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Fotocamera occupata da un'altra app"
                            else -> "Errore fotocamera (${error.code})"
                        }
                    }
                }
                videoCapture = capture
                updateStatus()

            } catch (fullBindError: Exception) {
                // Alcuni dispositivi non permettono ImageAnalysis + VideoCapture
                // contemporaneamente sulla camera frontale. In quel caso
                // manteniamo preview/stream/foto e disabilitiamo solo Video camera.
                try {
                    provider.unbind(analysis, capture)
                    ownedUseCases.clear()
                    provider.bindToLifecycle(
                        this,
                        selector,
                        analysis
                    )

                    ownedUseCases.add(analysis)
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

    private fun processFrame(image: ImageProxy, owner: Long, generation: Int) {
        var corrected: Bitmap? = null
        var small: Bitmap? = null
        try {
            if (destroyed || handoffPending || !pacer.accept(android.os.SystemClock.elapsedRealtime())) return
            corrected = BitmapFrames.corrected(image, userRotation.get())
            small = BitmapFrames.scaled(corrected, 480)
            val jpeg = ByteArrayOutputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, 55, out); out.toByteArray() }
            if (!destroyed && !handoffPending && owner == sourceToken && owner != 0L && generation == cameraGeneration) {
                latestCorrectedJpeg.set(jpeg)
                CameraStreamHub.updateFrame(owner, jpeg)
                presenter.offer(BitmapFrames.scaled(corrected, 640, copy = true))
            }
        } catch (e: Exception) {
            android.util.Log.e("CorrectedCamera", "Elaborazione frame Activity", e)
            CameraStreamHub.status(owner, "Errore immagine: ${e.javaClass.simpleName}")
        } finally {
            if (small !== corrected) small?.recycle()
            corrected?.recycle()
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
        destroyed = true; handoffPending = false
        handler.removeCallbacksAndMessages(null)
        presenter.close()
        activeRecording?.stop()
        activeRecording = null
        releaseOwnCamera()

        cameraExecutor.shutdown()

        super.onDestroy()
    }
}

