package ch.formazione.correctedcamera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

class FloatingCameraService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_STOP = "ch.formazione.correctedcamera.STOP_CAMERA"
        @Volatile var overlayVisible = false; private set
        const val EXTRA_FRONT = "front"
        const val EXTRA_ROTATION = "rotation"
        const val EXTRA_CIRCULAR = "circular"
        const val EXTRA_SIZE_MODE = "size_mode"
        const val EXTRA_SHOW_OVERLAY = "show_overlay"

        @Volatile
        var isRunning = false
            private set
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var overlayRoot: FrameLayout? = null
    private var imageView: ImageView? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var analysisUseCase: ImageAnalysis? = null
    @Volatile private var destroyed = false
    private var sourceToken = 0L
    private var errorLabel: TextView? = null
    private val presenter = BitmapPresenter { imageView }
    private val pacer = FramePacer()
    @Volatile private var bindGeneration = 0
    @Volatile private var useFrontCamera = false
    @Volatile private var requestedRotation = 0
    private var circular = true
    private var sizeMode = 1
    @Volatile private var showOverlay = true
    private var lastStreamFrameAt = 0L
    private var lastOverlayFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val oldFront = useFrontCamera
        useFrontCamera = intent.getBooleanExtra(EXTRA_FRONT, false)
        requestedRotation = intent.getIntExtra(EXTRA_ROTATION, 0)
        circular = intent.getBooleanExtra(EXTRA_CIRCULAR, true)
        sizeMode = intent.getIntExtra(EXTRA_SIZE_MODE, 1)
        showOverlay = intent.getBooleanExtra(EXTRA_SHOW_OVERLAY, true)
        if (showOverlay && !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            if (!isRunning) {
                sourceToken = CameraStreamHub.beginSource()
                startForeground(2201, notification("Avvio camera…"))
                isRunning = true
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
                mainHandler.post(healthCheck)
            }
            overlayVisible = showOverlay
            removeOverlay()
            if (showOverlay) createOverlay()
            if (analysisUseCase == null || oldFront != useFrontCamera) startCamera()
        } catch (e: Exception) {
            android.util.Log.e("CorrectedCamera", "Avvio servizio camera", e)
            CameraStreamHub.status(sourceToken, "Avvio camera non riuscito: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String): android.app.Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, FloatingCameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "corrected_camera_overlay")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Corrected Camera Test")
            .setContentText(message).setSubText("Creato da Dimitri Azzarone").setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ferma camera", stop).build()
    }
    private var lastHealthMessage = ""
    private val healthCheck = object : Runnable {
        override fun run() {
            if (destroyed) return
            val age = android.os.SystemClock.elapsedRealtime() - CameraStreamHub.lastFrameAt
            val message = if (CameraStreamHub.lastFrameAt > 0 && age > 3000 && CameraStreamHub.message == "Video attivo")
                "Video fermo · controlla se un'altra app usa la camera" else CameraStreamHub.message
            val healthy = message == "Video attivo"
            errorLabel?.text = message
            errorLabel?.visibility = if (healthy) View.GONE else View.VISIBLE
            if (message != lastHealthMessage) {
                lastHealthMessage = message
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@FloatingCameraService, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    getSystemService(NotificationManager::class.java).notify(2201, notification(message))
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun removeOverlay() {
        val root = overlayRoot ?: return

        try {
            if (::windowManager.isInitialized) {
                windowManager.removeView(root)
            }
        } catch (_: Exception) {
        }

        imageView?.setImageDrawable(null)
        presenter.clearPending()
        errorLabel = null
        imageView = null
        overlayRoot = null
    }

    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = overlaySizePx()

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val cameraView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        root.addView(
            cameraView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        if (circular) {
            cameraView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            cameraView.clipToOutline = true
        }

        errorLabel = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE); setBackgroundColor(0xcc162536.toInt())
            textSize = 12f; gravity = Gravity.CENTER; text = "Avvio camera…"
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(errorLabel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(90)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) returnToApp()
                    true
                }
                else -> false
            }
        }

        overlayRoot = root
        imageView = cameraView
        windowManager.addView(root, params)
    }

    private fun overlaySizePx(): Int {
        return when (sizeMode) {
            0 -> dp(130)
            1 -> dp(185)
            else -> dp(260)
        }
    }

    private fun startCamera() {
        val generation = ++bindGeneration
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (destroyed || generation != bindGeneration) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                analysisUseCase?.let { it.clearAnalyzer(); provider.unbind(it) }
                var selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) selector = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                require(provider.hasCamera(selector)) { "Nessuna fotocamera disponibile" }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640,480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysisUseCase = analysis
                analysis.setAnalyzer(cameraExecutor) { image ->
                    if (destroyed || generation != bindGeneration) image.close() else processFrame(image, generation)
                }
                val camera = provider.bindToLifecycle(this, selector, analysis)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    if (generation != bindGeneration || destroyed) return@observe
                    if (state.type == CameraState.Type.PENDING_OPEN)
                        CameraStreamHub.status(sourceToken, "Camera in attesa · possibile uso da un'altra app")
                    state.error?.let { error ->
                        val text = when(error.code) {
                            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Fotocamera occupata da un'altra app"
                            CameraState.ERROR_CAMERA_DISABLED -> "Fotocamera disabilitata dal sistema"
                            else -> "Errore fotocamera (${error.code})"
                        }
                        CameraStreamHub.status(sourceToken, text)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CorrectedCamera", "Collegamento camera", e)
                CameraStreamHub.status(sourceToken, "Camera non disponibile: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy, generation: Int) {
        var corrected: Bitmap? = null
        var small: Bitmap? = null
        try {
            if (destroyed || !pacer.accept(android.os.SystemClock.elapsedRealtime())) return
            corrected = BitmapFrames.corrected(image, requestedRotation)
            small = BitmapFrames.scaled(corrected, 480)
            val jpeg = ByteArrayOutputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, 55, out); out.toByteArray() }
            if (!destroyed && generation == bindGeneration) {
                CameraStreamHub.updateFrame(sourceToken, jpeg)
                if (showOverlay) presenter.offer(BitmapFrames.scaled(corrected, 420, copy = true))
            }
        } catch (e: Exception) {
            android.util.Log.e("CorrectedCamera", "Elaborazione frame", e)
            CameraStreamHub.status(sourceToken, "Errore immagine: ${e.javaClass.simpleName}")
        } finally {
            if (small !== corrected) small?.recycle()
            corrected?.recycle()
            image.close()
        }
    }

    private fun returnToApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        destroyed = true; bindGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        presenter.close()
        analysisUseCase?.let { it.clearAnalyzer(); cameraProvider?.unbind(it) }
        removeOverlay()
        cameraExecutor.shutdownNow()
        CameraStreamHub.endSource(sourceToken)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        isRunning = false; overlayVisible = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        "corrected_camera_overlay",
                        "Fotocamera in primo piano",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

