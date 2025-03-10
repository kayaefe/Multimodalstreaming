// app/src/main/java/com/example/multimodalstreaming/CameraManager.kt

package com.example.multimodalstreaming

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.graphics.YuvImage
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val viewFinder: PreviewView,
    private val coroutineScope: CoroutineScope,
    private val onImageCaptured: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "CameraManager"
        private val TARGET_RESOLUTION = Size(480, 360)
        private const val FPS_TARGET = 0.5f // 1 frame every 2 seconds
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var isCapturing = AtomicBoolean(false)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var frameJob: Job? = null

    suspend fun initialize() = suspendCoroutine<Unit> { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                continuation.resume(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera", e)
                continuation.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startCamera(cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA) {
        if (isCapturing.get()) {
            Log.w(TAG, "Camera already capturing")
            return
        }

        try {
            cameraProvider?.let { provider ->
                // Unbind any previous use cases
                provider.unbindAll()

                // Create preview use case
                preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                // Create image analysis use case
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(TARGET_RESOLUTION)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                // Bind use cases to camera
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                // Start capturing frames periodically
                isCapturing.set(true)
                startFrameCapture()

                Log.i(TAG, "Camera started with ${TARGET_RESOLUTION.width}x${TARGET_RESOLUTION.height} resolution")
            } ?: run {
                Log.e(TAG, "Camera provider not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting camera", e)
        }
    }

    private fun startFrameCapture() {
        frameJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isCapturing.get()) {
                captureFrame()
                // Calculate delay based on FPS_TARGET
                val delayMillis = (1000 / FPS_TARGET).toLong()
                delay(delayMillis)
            }
        }
    }

    private fun captureFrame() {
        try {
            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val jpegBytes = imageProxyToJpegByteArray(imageProxy)
                        withContext(Dispatchers.Main) {
                            onImageCaptured(jpegBytes)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image", e)
                    } finally {
                        imageProxy.close() // Always close the image proxy
                        // Remove this analyzer to prevent continuous callbacks
                        imageAnalysis?.clearAnalyzer()
                    }
                }

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing frame", e)
        }
    }

    fun stopCamera() {
        try {
            isCapturing.set(false)
            frameJob?.cancel()
            cameraProvider?.unbindAll()
            preview = null
            imageAnalysis = null
            camera = null
            Log.i(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    fun switchCamera() {
        val currentCameraSelector = if (camera?.cameraInfo?.let {
                it.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
            } == true) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        stopCamera()
        startCamera(currentCameraSelector)
    }

    private fun imageProxyToJpegByteArray(imageProxy: ImageProxy): ByteArray {
        val yBuffer = imageProxy.planes[0].buffer // Y plane
        val uBuffer = imageProxy.planes[1].buffer // U plane
        val vBuffer = imageProxy.planes[2].buffer // V plane

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are *swapped* in NV21
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize) // V before U
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 75, outputStream) // Quality: 75
        return outputStream.toByteArray()
    }

    fun cleanup() {
        stopCamera()
        cameraExecutor.shutdown()
    }
}