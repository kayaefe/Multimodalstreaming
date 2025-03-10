package com.example.multimodalstreaming

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.CompoundButton
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
import android.util.Log

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request only the "dangerous" permissions at startup
        if (!allPermissionsGranted()) {
            requestPermissions()
        } else {
            // Permissions are already granted, proceed with setup
            setupManagersAndUI()
        }
    }

    private fun setupManagersAndUI() {
        setupCameraManager()
        setupScreenCaptureManager()
        setupUI()
        observeViewModel()
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
        }

        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        binding.btnMicrophone.setOnClickListener {
            viewModel.toggleMicrophone()
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
            } else {
                viewModel.startScreenCapture()
                // Request screen capture permission using MediaProjectionManager
                val captureIntent = screenCaptureManager?.createCaptureIntent()
                if (captureIntent != null) {
                    screenCapturePermissionLauncher.launch(captureIntent) // This handles the permission
                } else {
                    Toast.makeText(this, "Could not create screen capture intent", Toast.LENGTH_SHORT).show()
                }
                binding.btnScreenCapture.text = "Stop Screen Capture"
            }
        }

        binding.btnSendMessage.setOnClickListener {
            val text = binding.etMessage.text.toString()
            viewModel.sendMessage(text)
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
                    binding.btnDisconnect.isEnabled = true
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
                // All "dangerous" permissions granted, proceed with setup
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
            screenCaptureManager?.onActivityResult(result.resultCode, result.data)
        } else {
            viewModel.stopScreenCapture()
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.cleanup()
    }
}