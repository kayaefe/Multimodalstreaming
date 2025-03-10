// MainActivity.kt
package com.example.multimodalstreaming

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.multimodalstreaming.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.INTERNET
        )
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var cameraManager: CameraManager? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var streamingService: StreamingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound = true
            viewModel.setServiceConnected() // Notify ViewModel
            observeService()
            Log.i(TAG, "Service connected")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            streamingService = null
            viewModel.setServiceDisconnected() // Notify ViewModel
            Log.i(TAG, "Service Disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Log.i(TAG, "Service binding died")
            isBound = false
            streamingService = null
            viewModel.setServiceDisconnected()
        }

        override fun onNullBinding(name: ComponentName?) {
            super.onNullBinding(name)
            Log.i(TAG, "Service null binding")
            isBound = false
            streamingService = null
            viewModel.setServiceDisconnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!allPermissionsGranted()) {
            requestPermissions()
        } else {
            setupManagersAndUI()
        }
    }

    private fun setupManagersAndUI() {
        setupCameraManager()
        setupScreenCaptureManager()
        setupUI()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        if (allPermissionsGranted()) {
            val serviceIntent = Intent(this, StreamingService::class.java)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE) // Bind, but don't *start*
        } // The foreground service will be started either by btnConnect OR screenCapturePermissionLauncher
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            streamingService = null // Important to avoid leaks
        }
    }

    private fun setupCameraManager() {
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            viewFinder = binding.viewFinder,
            coroutineScope = lifecycleScope,
            onImageCaptured = { imageData ->
                viewModel.sendImageData(imageData)
            }
        )

        lifecycleScope.launch {
            try {
                cameraManager?.initialize()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to initialize camera", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupScreenCaptureManager() {
        screenCaptureManager = ScreenCaptureManager(
            context = this,
            coroutineScope = lifecycleScope,
            onScreenshotCaptured = { imageData ->
                viewModel.sendImageData(imageData)
            }
        )
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            viewModel.connect()
            val serviceIntent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START // Regular start, no MediaProjection needed yet
                putExtra(StreamingService.EXTRA_API_KEY, MainViewModel.API_KEY)  // Pass API key here
            }
            startForegroundService(serviceIntent)
        }

        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        binding.btnMicrophone.setOnClickListener {
            streamingService?.toggleMicrophone() // Directly call service methods
        }

        binding.btnCamera.setOnClickListener {
            viewModel.toggleCamera()
        }

        binding.btnCamera.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                cameraManager?.startCamera(
                    viewModel.currentCameraSelector.value ?: CameraSelector.DEFAULT_BACK_CAMERA
                )
            } else {
                cameraManager?.stopCamera()
            }
        }

        binding.btnScreenCapture.setOnClickListener {
            if (viewModel.isScreenCaptureActive.value == true) {
                viewModel.stopScreenCapture()
                screenCaptureManager?.stopCapturing()
                binding.btnScreenCapture.text = "Start Screen Capture"
                // Stop the foreground service when screen capture is stopped.
                val serviceIntent = Intent(this, StreamingService::class.java).apply {
                    action = StreamingService.ACTION_STOP
                }
                stopService(serviceIntent) // Stop, don't just unbind.

            } else {
                // Request screen capture permission.
                val captureIntent = screenCaptureManager?.createCaptureIntent()
                if (captureIntent != null) {
                    screenCapturePermissionLauncher.launch(captureIntent)
                } else {
                    Toast.makeText(this, "Could not create screen capture intent", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSendMessage.setOnClickListener {
            val text = binding.etMessage.text.toString()
            streamingService?.sendTextMessage(text)  // Directly call service methods
            binding.etMessage.setText("")
        }

        binding.btnSwitchCamera.setOnClickListener {
            viewModel.switchCamera()
        }
    }

    private fun observeViewModel() {
        viewModel.connectionState.observe(this) { state ->
            when (state) {
                MainViewModel.ConnectionState.DISCONNECTED -> {
                    binding.statusText.text = "Disconnected"
                    binding.btnConnect.isEnabled = true
                    binding.btnDisconnect.isEnabled = false
                    binding.btnMicrophone.isEnabled = false
                    binding.btnCamera.isEnabled = false
                    binding.btnScreenCapture.isEnabled = false
                    binding.btnSendMessage.isEnabled = false
                    binding.btnSwitchCamera.isEnabled = false
                }
                MainViewModel.ConnectionState.CONNECTING -> {
                    binding.statusText.text = "Connecting..."
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true // Keep disconnect enabled
                    binding.btnSwitchCamera.isEnabled = false
                }
                MainViewModel.ConnectionState.CONNECTED -> {
                    binding.statusText.text = "Connected"
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    binding.btnMicrophone.isEnabled = true
                    binding.btnCamera.isEnabled = true
                    binding.btnScreenCapture.isEnabled = true
                    binding.btnSendMessage.isEnabled = true
                    binding.btnSwitchCamera.isEnabled = true
                }
                MainViewModel.ConnectionState.FAILED -> {
                    binding.statusText.text = "Connection Failed"
                    binding.btnConnect.isEnabled = true
                    binding.btnDisconnect.isEnabled = false // Disable disconnect if failed
                    binding.btnSwitchCamera.isEnabled = false
                }
            }
        }

        viewModel.isMicActive.observe(this) { isActive ->
            binding.btnMicrophone.isChecked = isActive
        }

        viewModel.isCameraActive.observe(this) { isActive ->
            binding.btnCamera.isChecked = isActive
        }

        viewModel.currentCameraSelector.observe(this) {
            if (viewModel.isCameraActive.value == true) {
                cameraManager?.switchCamera()
            }
        }

        viewModel.isScreenCaptureActive.observe(this) { isActive ->
            binding.btnScreenCapture.text = if (isActive) "Stop Screen Capture" else "Start Screen Capture"
        }

        viewModel.receivedText.observe(this) { text ->
            //Log.i(TAG, "Received in Activity: $text") // Commented out
        }
    }

    private fun observeService() {
        streamingService?.let { service ->
            service.serviceState.observe(this) { state ->
                viewModel.updateFromServiceState(state) // Update ViewModel
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }


    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                setupManagersAndUI()
            } else {
                Toast.makeText(
                    this,
                    "Camera, Microphone, Internet, or Foreground Service permissions not granted.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private val screenCapturePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // NOW we have the implicit permission.  Start the service.
            viewModel.startScreenCapture() // Set the flag
            val serviceIntent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START_SCREEN_CAPTURE
                putExtra(StreamingService.EXTRA_API_KEY, MainViewModel.API_KEY)
                putExtra(StreamingService.EXTRA_MEDIA_PROJECTION_RESULT_CODE, result.resultCode)
                putExtra(StreamingService.EXTRA_MEDIA_PROJECTION_DATA, result.data)
            }
            startForegroundService(serviceIntent) // Start foreground *after* permission

            screenCaptureManager?.onActivityResult(result.resultCode, result.data)
            binding.btnScreenCapture.text = "Stop Screen Capture"

        } else {
            viewModel.stopScreenCapture()
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            binding.btnScreenCapture.text = "Start Screen Capture"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        cameraManager?.cleanup()
    }
}