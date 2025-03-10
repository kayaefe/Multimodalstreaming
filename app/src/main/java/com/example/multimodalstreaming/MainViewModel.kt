// MainViewModel.kt
package com.example.multimodalstreaming

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.camera.core.CameraSelector

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
        const val API_KEY = "AIzaSyBIcvcoJAUini4EGWxOLWIv2GHbxqhiLuQ" // Replace and secure!
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

    private val _currentCameraSelector = MutableLiveData<CameraSelector>(CameraSelector.DEFAULT_BACK_CAMERA)
    val currentCameraSelector: LiveData<CameraSelector> = _currentCameraSelector

    private val _receivedText = MutableLiveData<String>()
    val receivedText: LiveData<String> = _receivedText

    private val _isScreenCaptureActive = MutableLiveData(false)
    val isScreenCaptureActive: LiveData<Boolean> = _isScreenCaptureActive

    // Add a method to update the ViewModel based on the ServiceState
    fun updateFromServiceState(state: StreamingService.ServiceState) {
        _receivedText.postValue(state.lastReceivedText)
        _isMicActive.postValue(state.isMicActive)
        _connectionState.postValue(if (state.isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
    }


    // Called when the service connects
    fun setServiceConnected() {
        _connectionState.value = ConnectionState.CONNECTED
    }

    // Called when the service disconnects
    fun setServiceDisconnected() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
    }

    fun disconnect() {
        // Send a disconnect command to the service.
        _connectionState.postValue(ConnectionState.DISCONNECTED)
        _isMicActive.postValue(false) // Ensure correct state
        _isCameraActive.postValue(false)
        _isScreenCaptureActive.postValue(false)
    }

    //Keep this function to start/stop the audio capture
    fun toggleMicrophone() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
    }

    fun toggleCamera() {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }
        _isCameraActive.value = !(_isCameraActive.value ?: false)
    }

    fun switchCamera() {
        // ... (same as before)
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
    fun sendImageData(imageData: ByteArray) {
        // No changes needed here, just forward the request.
    }
    // No need for onCleared, MainActivity handles unbinding.
}