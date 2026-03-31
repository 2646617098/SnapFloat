package com.codex.snapfloat.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.codex.snapfloat.CapturePermissionStore
import com.codex.snapfloat.MainActivity
import com.codex.snapfloat.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val isCapturing = AtomicBoolean(false)

    private var projectionResultCode: Int? = null
    private var projectionDataIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureMetrics: DisplayMetrics? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startInForeground()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cacheProjectionPermission(intent)
        if (overlayView == null) {
            showOverlay()
        }
        if (mediaProjection == null && projectionResultCode != null && projectionDataIntent != null) {
            try {
                startProjectionSession()
            } catch (t: Throwable) {
                toast(getString(R.string.capture_failed, "session-${t.javaClass.simpleName}"))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseProjectionSession()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_button, null)
        val button = view.findViewById<TextView>(R.id.captureButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 320
        }

        button.setOnTouchListener(createDragTouchListener(view, params))
        button.setOnClickListener {
            if (!isCapturing.compareAndSet(false, true)) return@setOnClickListener
            button.isEnabled = false
            Handler(Looper.getMainLooper()).postDelayed({
                captureScreenshot {
                    isCapturing.set(false)
                    button.isEnabled = true
                }
            }, 120)
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            toast(getString(R.string.overlay_visible))
        } catch (t: Throwable) {
            toast(getString(R.string.overlay_add_failed, t.javaClass.simpleName))
            stopSelf()
        }
    }

    private fun createDragTouchListener(
        view: View,
        params: WindowManager.LayoutParams
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(deltaX) > 6 || kotlin.math.abs(deltaY) > 6) {
                            moved = true
                            params.x = initialX + deltaX
                            params.y = initialY + deltaY
                            windowManager.updateViewLayout(view, params)
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) v.performClick()
                        return true
                    }
                }
                return true
            }
        }
    }

    private fun cacheProjectionPermission(intent: Intent?) {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != null && resultCode != Int.MIN_VALUE && dataIntent != null) {
            projectionResultCode = resultCode
            projectionDataIntent = Intent(dataIntent)
        }
    }

    private fun startProjectionSession() {
        val resultCode = projectionResultCode ?: CapturePermissionStore.resultCode
        val dataIntent = projectionDataIntent ?: CapturePermissionStore.dataIntent
        if (resultCode == null || dataIntent == null || mediaProjection != null) return

        promoteToMediaProjectionForeground()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, dataIntent)
        val metrics = resources.displayMetrics
        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            3
        )
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                releaseProjectionSession()
                toast(getString(R.string.capture_session_ended))
            }
        }

        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        val display = projection.createVirtualDisplay(
            "snapfloat-capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(Looper.getMainLooper())
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
        projectionCallback = callback
        captureMetrics = metrics
        toast(getString(R.string.capture_session_ready))
    }

    private fun releaseProjectionSession() {
        val projection = mediaProjection
        val callback = projectionCallback

        projectionCallback = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureMetrics = null
        mediaProjection = null

        if (projection != null && callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
    }

    private fun captureScreenshot(onComplete: () -> Unit) {
        val reader = imageReader
        val metrics = captureMetrics
        if (reader == null || metrics == null || virtualDisplay == null) {
            toast(getString(R.string.capture_permission_missing))
            onComplete()
            return
        }

        try {
            val image = reader.acquireLatestImage()
            if (image == null) {
                toast(getString(R.string.capture_frame_unavailable))
                onComplete()
                return
            }
            image.use {
                saveImage(it, metrics)
            }
        } catch (t: Throwable) {
            toast(getString(R.string.capture_failed, t.message ?: t.javaClass.simpleName))
        } finally {
            onComplete()
        }
    }

    private fun saveImage(image: Image, metrics: DisplayMetrics) {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * metrics.widthPixels

        val bitmap = Bitmap.createBitmap(
            metrics.widthPixels + rowPadding / pixelStride,
            metrics.heightPixels,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        val croppedBitmap =
            Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
        bitmap.recycle()

        val fileName = "SnapFloat_${DATE_FORMAT.format(Date())}.png"
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapFloat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { output ->
            if (!croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalStateException("Bitmap compression failed")
            }
        } ?: throw IllegalStateException("Cannot open output stream")

        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        croppedBitmap.recycle()
        toast(getString(R.string.capture_saved, uri.toString()))
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun promoteToMediaProjectionForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val CHANNEL_ID = "snapfloat_overlay"
        private const val NOTIFICATION_ID = 11
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        fun createIntent(context: Context): Intent = Intent(context, OverlayService::class.java)
    }
}
