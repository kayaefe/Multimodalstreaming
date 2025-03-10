package com.example.multimodalstreaming

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onScreenshotCaptured: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "ScreenCapture"
        private const val SCREENSHOT_INTERVAL_MS = 2000L // Take screenshot every 2 seconds
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    init {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        initScreenMetrics()
    }

    private fun initScreenMetrics() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        // Reduce resolution for efficiency
        screenWidth = (metrics.widthPixels * 0.5).toInt()
        screenHeight = (metrics.heightPixels * 0.5).toInt()
        screenDensity = metrics.densityDpi
    }

    fun createCaptureIntent(): Intent {
        return mediaProjectionManager?.createScreenCaptureIntent() ?: Intent()
    }

    fun onActivityResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Screen capture permission denied")
            return
        }

        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get media projection")
            return
        }

        setupVirtualDisplay()
        startCapturing()
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startCapturing() {
        if (isCapturing.get()) {
            Log.w(TAG, "Screen capture already in progress")
            return
        }

        isCapturing.set(true)

        captureJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isCapturing.get()) {
                captureScreenshot()
                delay(SCREENSHOT_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Started screen capturing")
    }

    private fun captureScreenshot() {
        try {
            imageReader?.acquireLatestImage()?.use { image ->
                val bitmap = imageToBitmap(image)
                val jpegBytes = bitmapToJpegByteArray(bitmap)
                onScreenshotCaptured(jpegBytes)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screenshot", e)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun bitmapToJpegByteArray(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return outputStream.toByteArray()
    }

    fun stopCapturing() {
        isCapturing.set(false)
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        Log.i(TAG, "Stopped screen capturing")
    }
}