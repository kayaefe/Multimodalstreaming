// app/src/main/java/com/example/multimodalstreaming/WebSocketClient.kt

package com.example.multimodalstreaming

import android.util.Base64
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

class WebSocketClient(
    private val apiKey: String,
    private val coroutineScope: CoroutineScope,
    private val onAudioDataReceived: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "WebSocketClient"
        private const val BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_BACKOFF_MS = 1000L
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        SETUP_COMPLETE, // Keep this for later logic
        RECONNECTING,
        FAILED
    }

    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: LiveData<ConnectionState> = _connectionState

    private val _receivedText = MutableLiveData<String>()
    val receivedText: LiveData<String> = _receivedText

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.SETUP_COMPLETE) {
            Log.w(TAG, "Already connecting or connected")
            return
        }

        _connectionState.postValue(ConnectionState.CONNECTING)
        // Construct the URL with the API key:
        val request = Request.Builder()
            .url("$BASE_URL?key=$apiKey") // NO api_version here
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    fun disconnect() {
        webSocket?.close(1000, "User initiated disconnect")
        webSocket = null
        _connectionState.postValue(ConnectionState.DISCONNECTED)
        reconnectAttempts = 0
    }

    fun sendTextMessage(text: String) {
        if (_connectionState.value != ConnectionState.SETUP_COMPLETE) {
            Log.w(TAG, "Cannot send message: Not connected or setup not complete")
            return
        }

        val message = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", text)
                            })
                        })
                    })
                })
                put("turnComplete", true) // Add turnComplete: true for text messages
            })
        }.toString()

        Log.d(TAG, "Sending Text Message: $message")
        webSocket?.send(message)
    }


    fun sendAudioData(audioData: ByteArray) {
        if (_connectionState.value != ConnectionState.SETUP_COMPLETE) {
            Log.w(TAG, "Cannot send audio: Not connected or setup not complete")
            return
        }

        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply { // mediaChunks is now directly under realtimeInput
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Audio)
                        })
                    })
                })
            })
        }.toString()

        Log.d(TAG, "Sending Audio Data: $message")
        webSocket?.send(message)
    }

    fun sendImageData(imageData: ByteArray) {
        if (_connectionState.value != ConnectionState.SETUP_COMPLETE) {
            Log.w(TAG, "Cannot send image: Not connected or setup not complete")
            return
        }

        val base64Image = Base64.encodeToString(imageData, Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply { // mediaChunks is now directly under realtimeInput
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                })
            })
        }.toString()
        Log.d(TAG, "Sending Image Data: $message")
        webSocket?.send(message)
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connection opened")
                _connectionState.postValue(ConnectionState.CONNECTED)
                reconnectAttempts = 0

                // Send setup message
                val setupMessage = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", "models/gemini-2.0-flash-exp") // Correct model name
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", "audio")
                        })
                    })
                }.toString()

                Log.d(TAG, "Sending Setup Message: $setupMessage")
                webSocket.send(setupMessage)


                // No initial text message sent immediately after setup anymore.
                // Send text or audio/image only when user interacts.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                try {
                    val json = JSONObject(text)

                    if (json.has("setupComplete")) {
                        Log.i(TAG, "Setup Complete message received")
                        _connectionState.postValue(ConnectionState.SETUP_COMPLETE)

                    } else if (json.has("serverContent")) {
                        val serverContent = json.getJSONObject("serverContent")
                        if (serverContent.has("modelTurn")) {
                            val modelTurn = serverContent.getJSONObject("modelTurn")
                            val parts = modelTurn.getJSONArray("parts")
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.has("text")) {
                                    val textContent = part.getString("text")
                                    Log.i(TAG, "Received Text: $textContent")
                                    _receivedText.postValue(textContent)
                                } else if (part.has("inlineData")) {
                                    val inlineData = part.getJSONObject("inlineData")
                                    val mimeType = inlineData.getString("mimeType")
                                    if (mimeType.startsWith("audio/pcm")) {
                                        val base64Audio = inlineData.getString("data")
                                        val audioByteArray = Base64.decode(base64Audio, Base64.DEFAULT)
                                        Log.i(TAG, "Received Audio: ${audioByteArray.size} bytes")
                                        onAudioDataReceived(audioByteArray)
                                    }
                                }
                            }
                        } else if (serverContent.has("turnComplete")) {
                            Log.i(TAG, "Turn Complete")
                        } else if (serverContent.has("interrupted")) {
                            Log.i(TAG, "Interrupted")
                        }
                    } else {
                        Log.w(TAG, "Unhandled message: $text")
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Error parsing JSON: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code, $reason")
                webSocket.close(1000, "Acknowledged close") // Normal closure
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code, $reason")
                if (code != 1000 && code != 1011) { // Not normal closure and not server error
                    attemptReconnect()
                } else {
                    _connectionState.postValue(ConnectionState.DISCONNECTED)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                attemptReconnect() // Try to reconnect on failure
            }
        }
    }

    private fun attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached. Giving up.")
            _connectionState.postValue(ConnectionState.FAILED)
            return
        }

        _connectionState.postValue(ConnectionState.RECONNECTING)
        reconnectAttempts++

        val delayMs = INITIAL_BACKOFF_MS * 2.0.pow(reconnectAttempts.toDouble()).toLong()
        val cappedDelayMs = min(delayMs, 30000L) // Cap at 30 seconds

        coroutineScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Attempting to reconnect in $cappedDelayMs ms (attempt $reconnectAttempts)")
            delay(cappedDelayMs)
            connect()
        }
    }
}