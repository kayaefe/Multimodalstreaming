// app/src/main/java/com/example/multimodalstreaming/AudioRecorder.kt

package com.example.multimodalstreaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

class AudioRecorder(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onAudioDataCaptured: (ByteArray) -> Unit,
    private val onPermissionDenied: () -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 2048 // in shorts (4096 bytes)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    fun start() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        // Check for recording permission
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Recording permission not granted")
            onPermissionDenied()
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val actualBufferSize = bufferSize * 2 // Use a larger buffer to avoid underruns

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                actualBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(CHUNK_SIZE)

                while (isActive && isRecording) {
                    val readResult = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0

                    if (readResult > 0) {
                        val byteArray = shortArrayToByteArray(shortBuffer, readResult)
                        onAudioDataCaptured(byteArray)
                    } else {
                        Log.w(TAG, "Audio read error: $readResult")
                    }
                }
            }

            Log.i(TAG, "Started audio recording")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while recording audio", e)
            onPermissionDenied()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording", e)
        }
    }

    fun stop() {
        try {
            isRecording = false
            recordingJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i(TAG, "Stopped audio recording")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
        }
    }

    private fun shortArrayToByteArray(shortArray: ShortArray, size: Int): ByteArray {
        val byteArray = ByteArray(size * 2)
        for (i in 0 until size) {
            val shortValue = shortArray[i]
            // Corrected:  Use toInt() and bitwise AND.  This is the best approach.
            byteArray[i * 2] = (shortValue.toInt() and 0xFF).toByte()      // Little-endian: Low byte
            byteArray[i * 2 + 1] = (shortValue.toInt() shr 8).toByte()    // Little-endian: High byte
        }
        return byteArray
    }
}