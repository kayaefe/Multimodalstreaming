package com.example.multimodalstreaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import android.Manifest

class StreamingService : LifecycleService() {
    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "MultimodalStreamingChannel"
        private const val CHANNEL_NAME = "Multimodal Streaming Service"

        // Service actions
        const val ACTION_START = "com.example.multimodalstreaming.START"
        const val ACTION_STOP = "com.example.multimodalstreaming.STOP"
        const val ACTION_TOGGLE_MIC = "com.example.multimodalstreaming.TOGGLE_MIC"
        const val ACTION_TOGGLE_CAMERA = "com.example.multimodalstreaming.TOGGLE_CAMERA"

        // Extra keys
        const val EXTRA_API_KEY = "api_key"
    }

    private lateinit var webSocketClient: WebSocketClient
    private var audioRecorder: AudioRecorder? = null // Make nullable
    private lateinit var audioPlayer: AudioPlayer

    // Use LiveData for better integration with Android ViewModel
    private val _serviceState = MutableLiveData(ServiceState())
    val serviceState: LiveData<ServiceState> = _serviceState

    private var isServiceRunning = false
    private var apiKey: String? = null

    // Binder for client interaction
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

        when (intent?.action) {
            ACTION_START -> {
                apiKey = intent.getStringExtra(EXTRA_API_KEY)
                if (apiKey.isNullOrEmpty()) {
                    Log.e(TAG, "API key is required")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundService()
                setupComponents()
                connectWebSocket()
                isServiceRunning = true
            }
            ACTION_STOP -> {
                stopMyService()
            }
            ACTION_TOGGLE_MIC -> {
                toggleMicrophone()
            }
            ACTION_TOGGLE_CAMERA -> {
                toggleCamera()
            }
        }

        return START_STICKY
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
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Use a standard icon
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
                NotificationManager.IMPORTANCE_LOW  // Use IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupComponents() {
        audioPlayer = AudioPlayer(lifecycleScope) // Initialize here
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
                audioRecorder = null //Release for garbage collection
                _serviceState.value = currentState.copy(isMicActive = false)
            } else {
                //Only instantiate if needed
                if (audioRecorder == null) {
                    audioRecorder = AudioRecorder(
                        context = this, // Pass the service context!
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

            // Add any camera-specific logic here if needed

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
        audioRecorder?.stop() // Stop if active
        audioRecorder = null


        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }

        if (::audioPlayer.isInitialized) {
            audioPlayer.release()
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        else{
            @Suppress("DEPRECATION")
            stopForeground(true)
        }


        stopSelf()
        isServiceRunning = false

        Log.i(TAG, "Service stopped")
    }

    override fun onDestroy() {
        if(isServiceRunning){
            stopMyService()
        }
        super.onDestroy()
        Log.i(TAG, "Service destroyed")

    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
}