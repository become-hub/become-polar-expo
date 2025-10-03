package com.become.augmentedperformance.presentation

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

// Response from /auth/device/start
data class DeviceStartResponse(
    val code: String,
    val deviceToken: String,
    val expiresAt: Long
)

// Response from /auth/device/confirm
data class DevicePollResponse(
    val authenticated: Boolean,
    val userId: String,
    val session: String,
    val deviceCode: String   // now populated from confirm
)

class AuthService {
    companion object {
        private const val TAG = "AuthService"
    }
    private val serverUrl = "https://production-api25.become-hub.com"
    private val client = OkHttpClient()

    /**
     * Step 1: kick off the device code flow
     */
    @Throws(IOException::class)
    suspend fun startDeviceAuth(): DeviceStartResponse? {
        val request = Request.Builder()
            .url("$serverUrl/auth/device/start")
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            return DeviceStartResponse(
                code        = json.getString("code"),
                deviceToken = json.getString("deviceToken"),
                expiresAt   = json.getLong("expiresAt")
            )
        }
    }

    /**
     * Step 2: poll the confirm endpoint until the user logs in on the PC.
     * Now points at /auth/device/confirm and parses back the new deviceCode field.
     */
    @Throws(IOException::class)
    suspend fun pollDeviceAuth(deviceToken: String): DevicePollResponse? {
        val request = Request.Builder()
            .url("$serverUrl/auth/device/pool?deviceToken=$deviceToken")
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            // 1) Read the entire body exactly once
            val bodyString = resp.body?.string() ?: return null

            // 2) Log it
            Log.d("AuthService", "pollDeviceAuth raw response: $bodyString")

            // 3) Only then parse it
            if (!resp.isSuccessful) return null
            val json = JSONObject(bodyString)

            // 4) Safely extract fields
            val authenticated = json.optBoolean("authenticated", false)
            val uid           = json.optString("userId", "")
            val session       = json.optString("session", "")
            val deviceCode    = json.optString("deviceCode", "")

            return DevicePollResponse(
                authenticated = authenticated,
                userId        = uid,
                session       = session,
                deviceCode    = deviceCode
            )
        }
    }
}
