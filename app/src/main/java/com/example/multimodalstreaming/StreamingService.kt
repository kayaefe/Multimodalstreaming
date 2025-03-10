// StreamingService.kt
package com.example.multimodalstreaming

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcelable // Import Parcelable
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope

class StreamingService : LifecycleService() {

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "MultimodalStreamingChannel"
        private const val CHANNEL_NAME = "Multimodal Streaming Service"

        const val ACTION_START = "com.example.multimodalstreaming.START"
        const val ACTION_STOP = "com.example.multimodalstreaming.STOP"
        const val ACTION_TOGGLE_MIC = "com.example.multimodalstreaming.TOGGLE_MIC"
        const val ACTION_TOGGLE_CAMERA = "com.example.multimodalstreaming.TOGGLE_CAMERA"
        const val ACTION_START_SCREEN_CAPTURE = "com.example.multimodalstreaming.START_SCREEN_CAPTURE"

        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MEDIA_PROJECTION_RESULT_CODE = "media_projection_result_code"
        const val EXTRA_MEDIA_PROJECTION_DATA = "media_projection_data"
    }

    private lateinit var webSocketClient: WebSocketClient
    private var audioRecorder: AudioRecorder? = null
    private lateinit var audioPlayer: AudioPlayer

    private val _serviceState = MutableLiveData(ServiceState())
    val serviceState: LiveData<ServiceState> = _serviceState

    private var isServiceRunning = false
    private var apiKey: String? = null
    private var mediaProjection: MediaProjection? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    data class ServiceState(
        val isConnected: Boolean = false,
        val isMicActive: Boolean = false,
        val isCameraActive: Boolean = false,
        val lastReceivedText: String = ""
    )

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand: intent=$intent, flags=$flags, startId=$startId")

        when (intent?.action) {
            ACTION_START -> { // Regular start, without MediaProjection
                if (!isServiceRunning) {
                    apiKey = intent.getStringExtra(EXTRA_API_KEY)
                    if (apiKey.isNullOrEmpty()) {
                        Log.e(TAG, "API key is required")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    saveApiKey(apiKey!!)

                    startForegroundService() // Start foreground
                    setupComponents()
                    connectWebSocket()
                    isServiceRunning = true
                }
            }
            ACTION_START_SCREEN_CAPTURE -> { // Start with MediaProjection
                if (!isServiceRunning) {
                    apiKey = intent.getStringExtra(EXTRA_API_KEY)
                    val resultCode = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, -1)

                    // Use the deprecated method and suppress the warning:
                    @Suppress("DEPRECATION")
                    val data: Intent? = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA)

                    if (apiKey.isNullOrEmpty() || resultCode == -1 || data == null) {
                        Log.e(TAG, "API key, result code and data are required")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    saveApiKey(apiKey!!)

                    startForegroundService() // Start foreground *FIRST*
                    val mediaProjectionManager =
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data) // *Then* get MediaProjection
                    if (mediaProjection == null) {
                        Log.e(TAG, "Failed to get MediaProjection")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    isServiceRunning = true
                }
            }
            ACTION_STOP -> stopMyService()
            ACTION_TOGGLE_MIC -> toggleMicrophone()
            ACTION_TOGGLE_CAMERA -> toggleCamera()
            null -> { // Handle null intent (system restart)
                Log.i(TAG, "Service restarted by the system with a null intent.")
                if (isServiceRunning) {
                    apiKey = loadApiKey()
                    if (apiKey != null) {
                        startForegroundService()
                        setupComponents()
                        connectWebSocket()
                    } else {
                        Log.e(TAG, "API Key is null")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                } else {
                    Log.i(TAG, "Service wasn't running on restart")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }
    private fun saveApiKey(apiKey: String) {
        getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("api_key", apiKey)
            .apply()
    }

    private fun loadApiKey(): String? {
        return getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            .getString("api_key", null)
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Multimodal Streaming")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Service started in foreground")
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupComponents() {
        audioPlayer = AudioPlayer(lifecycleScope)
        audioPlayer.initialize()

        webSocketClient = WebSocketClient(
            apiKey = apiKey ?: "",
            coroutineScope = lifecycleScope,
            onAudioDataReceived = { audioData ->
                audioPlayer.playAudio(audioData)
            }
        )

        webSocketClient.connectionState.observe(this) { state ->
            _serviceState.value = _serviceState.value?.copy(
                isConnected = state == WebSocketClient.ConnectionState.SETUP_COMPLETE
            )
            updateNotification()
        }

        webSocketClient.receivedText.observe(this) { text ->
            _serviceState.value = _serviceState.value?.copy(lastReceivedText = text)
        }
    }
    private fun connectWebSocket() {
        if (::webSocketClient.isInitialized) {
            webSocketClient.connect()
        } else {
            Log.e(TAG, "WebSocketClient not initialized")
        }
    }

    fun toggleMicrophone() {
        val currentState = _serviceState.value
        if (currentState != null) {
            if (currentState.isMicActive) {
                audioRecorder?.stop()
                audioRecorder = null
                _serviceState.value = currentState.copy(isMicActive = false)
            } else {
                if (audioRecorder == null) {
                    audioRecorder = AudioRecorder(
                        context = this,
                        coroutineScope = lifecycleScope,
                        onAudioDataCaptured = { audioData ->
                            webSocketClient.sendAudioData(audioData)
                        }
                    )
                }
                audioRecorder?.start()
                _serviceState.value = currentState.copy(isMicActive = true)
            }
            updateNotification()
        }
    }

    fun toggleCamera() {
        val currentState = _serviceState.value
        if (currentState != null) {
            val newCameraState = !currentState.isCameraActive
            _serviceState.value = currentState.copy(isCameraActive = newCameraState)
            updateNotification()
        }
    }

    fun isMicrophoneActive(): Boolean {
        return _serviceState.value?.isMicActive ?: false
    }

    fun sendTextMessage(text: String) {
        if (::webSocketClient.isInitialized) {
            webSocketClient.sendTextMessage(text)
        } else {
            Log.e(TAG, "WebSocketClient not initialized")
        }
    }

    fun sendImageData(imageData: ByteArray) {
        if (::webSocketClient.isInitialized) {
            webSocketClient.sendImageData(imageData)
        } else {
            Log.e(TAG, "WebSocketClient not initialized")
        }
    }
    private fun updateNotification() {
        val currentState = _serviceState.value ?: return

        val status = if (currentState.isConnected) "Connected" else "Disconnected"
        val mic = if (currentState.isMicActive) "Mic ON" else "Mic OFF"
        val camera = if (currentState.isCameraActive) "Camera ON" else "Camera OFF"

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Multimodal Streaming")
            .setContentText("$status | $mic | $camera")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopMyService() {
        Log.i(TAG, "Stopping service...")
        audioRecorder?.stop()
        audioRecorder = null
        mediaProjection?.stop()
        mediaProjection = null

        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
        if (::audioPlayer.isInitialized) {
            audioPlayer.release()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isServiceRunning = false
        Log.i(TAG, "Service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
}