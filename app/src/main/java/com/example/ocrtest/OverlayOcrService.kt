package com.example.ocrtest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OverlayOcrService : Service() {
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val isRecognizing = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlayButton: View? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        // Foreground service keeps MediaProjection alive while the overlay is visible.
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addOverlayButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_CANCELED) ?: RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (mediaProjection == null && resultCode == RESULT_OK && data != null) {
            // MediaProjection setup must happen after the user grants capture permission.
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the overlay and projection resources when the service stops.
        overlayButton?.let { windowManager?.removeView(it) }
        overlayButton = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        ocrExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupVirtualDisplay() {
        val displayMetrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(it)
        }
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ocr-screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun addOverlayButton() {
        val button = Button(this).apply {
            text = "OCR"
            setOnClickListener { runOcrOnLatestFrame() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 32
            y = 120
        }
        windowManager?.addView(button, params)
        overlayButton = button
    }

    private fun runOcrOnLatestFrame() {
        if (!isRecognizing.compareAndSet(false, true)) {
            return
        }
        ocrExecutor.execute {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                isRecognizing.set(false)
                Log.w(TAG, "No frame available for OCR.")
                return@execute
            }
            val bitmap = imageToBitmap(image)
            image.close()
            if (bitmap == null) {
                isRecognizing.set(false)
                Log.w(TAG, "Failed to convert frame to bitmap.")
                return@execute
            }
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            // ML Kit runs the recognition asynchronously and reports results on completion.
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    Log.d(TAG, "OCR result:\n${result.text}")
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "OCR failed.", error)
                }
                .addOnCompleteListener {
                    isRecognizing.set(false)
                }
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        // Crop out the padding so ML Kit sees the real screen size.
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun buildNotification(): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "OCR Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("OCR overlay running")
            .setContentText("Tap the floating OCR button to scan the screen.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "OverlayOcrService"
        private const val NOTIFICATION_CHANNEL_ID = "ocr_overlay"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
