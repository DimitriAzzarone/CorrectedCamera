package ch.formazione.correctedcamera

import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.widget.ImageView
import androidx.camera.core.CameraSelector
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

    private var useFrontCamera = false
    private var requestedRotation = 0
    private var circular = true
    private var sizeMode = 1
    private var showOverlay = true
    private var lastStreamFrameAt = 0L
    private var lastOverlayFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        createNotificationChannel()
        startForeground(
            2201,
            NotificationCompat.Builder(this, "corrected_camera_overlay")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Corrected Camera")
                .setContentText("Trasmissione camera attiva")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        useFrontCamera = intent?.getBooleanExtra(EXTRA_FRONT, false) ?: false
        requestedRotation = intent?.getIntExtra(EXTRA_ROTATION, 0) ?: 0
        circular = intent?.getBooleanExtra(EXTRA_CIRCULAR, true) ?: true
        sizeMode = intent?.getIntExtra(EXTRA_SIZE_MODE, 1) ?: 1
        showOverlay = intent?.getBooleanExtra(EXTRA_SHOW_OVERLAY, true) ?: true

        if (showOverlay && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        CameraStreamHub.start()

        if (showOverlay) {
            if (overlayRoot == null) {
                createOverlay()
            }
        } else {
            removeOverlay()
        }

        if (cameraProvider == null) {
            startCamera()
        }

        return START_STICKY
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
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            var selector =
                if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

            if (!provider.hasCamera(selector)) {
                selector =
                    if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
            }

            if (!provider.hasCamera(selector)) {
                stopSelf()
                return@addListener
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image -> processFrame(image) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (_: Exception) {
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        var cropped: Bitmap? = null
        var transformed: Bitmap? = null
        var preview: Bitmap? = null

        try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(
                paddedWidth, height, Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)

            cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            padded.recycle()

            val finalRotation =
                (image.imageInfo.rotationDegrees + requestedRotation) % 360

            transformed =
                if (finalRotation == 0) {
                    cropped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    val matrix = Matrix().apply {
                        postRotate(finalRotation.toFloat())
                    }
                    Bitmap.createBitmap(
                        cropped, 0, 0, cropped.width, cropped.height, matrix, true
                    )
                }

            val now = android.os.SystemClock.elapsedRealtime()

            if (now - lastStreamFrameAt >= 50L) {
                lastStreamFrameAt = now

                val streamBitmap = scaleForStream(transformed)

                val jpeg =
                    ByteArrayOutputStream().use { output ->
                        streamBitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            64,
                            output
                        )
                        output.toByteArray()
                    }

                if (streamBitmap !== transformed) {
                    streamBitmap.recycle()
                }

                CameraStreamHub.updateFrame(jpeg)
            }

            preview =
                if (showOverlay && now - lastOverlayFrameAt >= 66L) {
                    lastOverlayFrameAt = now
                    scaleForOverlay(transformed)
                } else {
                    null
                }

            val frameToShow = preview

            if (frameToShow != null) mainHandler.post {
                val iv = imageView
                if (iv != null && frameToShow != null) {
                    val old = iv.drawable
                    iv.setImageBitmap(frameToShow)
                    if (old is android.graphics.drawable.BitmapDrawable) {
                        val oldBitmap = old.bitmap
                        if (oldBitmap !== frameToShow && !oldBitmap.isRecycled) {
                            oldBitmap.recycle()
                        }
                    }
                } else {
                    frameToShow?.recycle()
                }
            }

            if (frameToShow != null) {
                preview = null
            }
        } finally {
            preview?.recycle()
            transformed?.recycle()
            cropped?.recycle()
            image.close()
        }
    }

    private fun scaleForStream(source: Bitmap): Bitmap {
        val maxSide = 640
        val largest = maxOf(source.width, source.height)

        if (largest <= maxSide) {
            return source
        }

        val scale = maxSide.toFloat() / largest.toFloat()
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())

        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun scaleForOverlay(source: Bitmap): Bitmap {
        val maxSide = 420
        val largest = maxOf(source.width, source.height)

        if (largest <= maxSide) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = maxSide.toFloat() / largest.toFloat()
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())

        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun returnToApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        } finally {
            stopSelf()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        removeOverlay()
        cameraExecutor.shutdownNow()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        isRunning = false
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
