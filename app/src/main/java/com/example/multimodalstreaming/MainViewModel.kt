package com.example.multimodalstreaming

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LifecycleOwner // Import LifecycleOwner

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
        private const val API_KEY = "AIzaSyBIcvcoJAUini4EGWxOLWIv2GHbxqhiLuQ" // Replace with your actual API key.  MOVE TO SECURE STORAGE!
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: LiveData<ConnectionState> = _connectionState

    private val _isMicActive = MutableLiveData(false)
    val isMicActive: LiveData<Boolean> = _isMicActive

    private val _isCameraActive = MutableLiveData(false)
    val isCameraActive: LiveData<Boolean> = _isCameraActive

    private val _currentCameraSelector =
        MutableLiveData<CameraSelector>(CameraSelector.DEFAULT_BACK_CAMERA)
    val currentCameraSelector: LiveData<CameraSelector> = _currentCameraSelector

    private val _receivedText = MutableLiveData<String>()
    val receivedText: LiveData<String> = _receivedText

    private val _isScreenCaptureActive = MutableLiveData(false)
    val isScreenCaptureActive: LiveData<Boolean> = _isScreenCaptureActive

    private var streamingService: StreamingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound = true
            _connectionState.postValue(ConnectionState.CONNECTED) // Update connection state
            Log.i(TAG, "Service connected")
            // Observe Service
            observeService()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            _connectionState.postValue(ConnectionState.DISCONNECTED)
            Log.i(TAG, "Service Disconnected")
        }
    }

    private fun observeService() {
        streamingService?.let { service ->
            // Observe received text
            // Corrected: Observe within the ViewModel's lifecycle scope.
            service.serviceState.observe(getApplication<Application>() as LifecycleOwner) { state ->
                _receivedText.postValue(state.lastReceivedText)
                _isMicActive.postValue(state.isMicActive)

                // Map service connection state to view model connection state
                val connectionState = when {
                    state.isConnected -> ConnectionState.CONNECTED
                    else -> _connectionState.value ?: ConnectionState.DISCONNECTED
                }
                _connectionState.postValue(connectionState)
            }
        }
    }

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        val serviceIntent = Intent(getApplication(), StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_API_KEY, API_KEY)
        }
        // Bind to the service
        getApplication<Application>().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        // Start the service (for foreground notification)
        getApplication<Application>().startForegroundService(serviceIntent)
    }

    fun disconnect() {
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
        val serviceIntent = Intent(getApplication(), StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        getApplication<Application>().startService(serviceIntent) // Use startService to ensure correct lifecycle
        _connectionState.postValue(ConnectionState.DISCONNECTED)
        _isMicActive.postValue(false) // Ensure correct state
        _isCameraActive.postValue(false)
        _isScreenCaptureActive.postValue(false)
    }

    fun toggleMicrophone() {
        if (!isBound) return

        val serviceIntent = Intent(getApplication(), StreamingService::class.java).apply {
            action = StreamingService.ACTION_TOGGLE_MIC
        }
        getApplication<Application>().startService(serviceIntent) // Use startService for commands
        // The state will be updated via observation of the service state
    }

    fun toggleCamera() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
        _isCameraActive.value = !(_isCameraActive.value ?: false)

        // Notify the service if needed
        if (isBound) {
            val serviceIntent = Intent(getApplication(), StreamingService::class.java).apply {
                action = StreamingService.ACTION_TOGGLE_CAMERA
            }
            getApplication<Application>().startService(serviceIntent)
        }
    }

    fun switchCamera() {
        if (_connectionState.value != ConnectionState.CONNECTED ||
            _isCameraActive.value != true) {
            return
        }

        _currentCameraSelector.value = if (_currentCameraSelector.value == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    fun startScreenCapture() {
        _isScreenCaptureActive.value = true
    }

    fun stopScreenCapture() {
        _isScreenCaptureActive.value = false
    }

    fun sendMessage(text: String) {
        if (isBound) {
            streamingService?.sendTextMessage(text)
        }
    }

    fun sendImageData(imageData: ByteArray) {
        if (isBound) {
            streamingService?.sendImageData(imageData)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect() // Unbind and stop service when ViewModel is cleared
    }
}