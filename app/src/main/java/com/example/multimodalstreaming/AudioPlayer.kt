package com.example.multimodalstreaming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPlayer(private val coroutineScope: CoroutineScope) {
    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 24000 // As specified in the PRD
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var isInitialized = false

    fun initialize() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            val attributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isInitialized = true
            Log.i(TAG, "AudioPlayer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioPlayer", e)
        }
    }

    fun playAudio(audioData: ByteArray) {
        if (!isInitialized) {
            initialize()
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val shortArray = byteArrayToShortArray(audioData)
                audioTrack?.write(shortArray, 0, shortArray.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio", e)
            }
        }
    }

    private fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortArray = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)
        return shortArray
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            isInitialized = false
            Log.i(TAG, "AudioPlayer released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioPlayer", e)
        }
    }
}