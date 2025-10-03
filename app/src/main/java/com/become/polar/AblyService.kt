
/*
 * AblyService.kt
 * Sends presence only once to avoid repeated "connected devices" in browser.
 */
package com.become.augmentedperformance.presentation

import android.util.Log
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.realtime.ConnectionEvent
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Param
import org.json.JSONObject

enum class ConnectionStatus { CONNECTED, CONNECTING, DISCONNECTED }

class AblyService(
    private val ablyTokenEndpoint: String,
    private val connectionStatusCallback: (ConnectionStatus) -> Unit
) {
    private lateinit var ably: AblyRealtime
    private var isConnected = false
    private var presenceEntered = false  // Guard to send presence only once

    fun connectWithToken(authToken: String, userId: Int, deviceCode: String) {
        Log.i("AblyService", "🚀 Connecting to Ably with token")
        val options = ClientOptions().apply {
            authUrl = ablyTokenEndpoint
            authHeaders = arrayOf(Param("Authorization", "Bearer $authToken"))
        }
        ably = AblyRealtime(options)
        setupConnectionListener(userId, deviceCode)
    }

    private fun setupConnectionListener(userId: Int, deviceCode: String) {
        ably.connection.on { stateChange ->
            when (stateChange.event) {
                ConnectionEvent.connecting -> {
                    isConnected = false
                    Log.d("AblyService", "⏳ Connecting...")
                    connectionStatusCallback(ConnectionStatus.CONNECTING)
                }
                ConnectionEvent.connected -> {
                    isConnected = true
                    Log.d("AblyService", "✅ Connected!")
                    connectionStatusCallback(ConnectionStatus.CONNECTED)
                    // Enter presence only once to avoid repeats
                    if (!presenceEntered) {
                        val channel = ably.channels.get("private:$userId")
                        val presenceData = JSONObject().apply { put("deviceCode", deviceCode) }
                        channel.presence.enter(
                            presenceData.toString(),
                            object : CompletionListener {
                                override fun onSuccess() {
                                    Log.d("AblyService", "🤝 Presence entered once: $deviceCode")
                                    presenceEntered = true
                                }
                                override fun onError(error: ErrorInfo?) {
                                    Log.e("AblyService", "❌ Presence error: ${error?.message}")
                                }
                            }
                        )
                    }
                }
                ConnectionEvent.failed,
                ConnectionEvent.disconnected -> {
                    isConnected = false
                    Log.e("AblyService", "🚫 Connection issue: ${stateChange.reason?.message}")
                    connectionStatusCallback(ConnectionStatus.DISCONNECTED)
                }
                else -> Log.d("AblyService", "🔄 Event: ${stateChange.event}")
            }
        }
    }

    fun sendHeartRate(deviceCode: String, userId: Int, bpm: Int, hrv: Int, lf: Int, hf: Int) {
        if (!::ably.isInitialized || !isConnected) {
            Log.w("AblyService", "⚠️ Cannot send, not connected")
            return
        }
        try {
            val channel = ably.channels.get("private:$userId")

            val message = JSONObject().apply {
                put("heartRate", bpm)
                put("hrv", hrv)
                put("lf", lf)
                put("hf", hf)
                put("code", deviceCode)
                put("type", "private_msg")
            }
            channel.publish("heartRate", message.toString())
            Log.d("AblyService", "📨 Sent heartRate=$bpm to private:$userId")
        } catch (e: Exception) {
            Log.e("AblyService", "❌ Failed to send heart rate: ${e.message}")
        }
    }

    fun close() {
        if (::ably.isInitialized) {
            ably.close()
            Log.d("AblyService", "🔒 Connection closed")
        }
    }
}
