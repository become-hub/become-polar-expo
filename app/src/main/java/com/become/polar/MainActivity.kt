package com.become.polar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.become.augmentedperformance.presentation.AblyService
import com.become.augmentedperformance.presentation.AuthService
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarPpiData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt
import org.jtransforms.fft.DoubleFFT_1D


class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // UI
    private lateinit var tvBluetooth: TextView
    private lateinit var tvStreaming: TextView
    private lateinit var tvAuthCode: TextView
    private lateinit var tvHeartRate: TextView
    private lateinit var tvHrv: TextView
    private lateinit var tvLfPower: TextView
    private lateinit var tvHfPower: TextView

    // Polar & Rx
    private val api: PolarBleApi by lazy {
        PolarBleApiDefaultImpl.defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING, // 🌐
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR, // ❤️
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
            )
        )
    }
    private var ppiDisposable: Disposable? = null
    private var batteryDisposable: Disposable? = null


    // Auth & Ably
    private lateinit var authService: AuthService
    private lateinit var ablyService: AblyService
    private var code = ""
    private var deviceCode = ""
    private var deviceToken = ""
    private var expiresAt = 0L
    private var authToken = ""
    private var userId = 0

    // HRV calculation
    private val ppiWindow = mutableListOf<Float>()
    private val WINDOW_SIZE = 30  // ultimi 30 battiti (~30 s)
    private var hrvValue = 0       // per tenere l’ultimo RMSSD calcolato


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // bind views
        tvBluetooth = findViewById(R.id.tv_bluetooth_status)
        tvStreaming = findViewById(R.id.tv_streaming_status)
        tvAuthCode = findViewById(R.id.tv_auth_code)
        tvHeartRate = findViewById(R.id.tv_heart_rate)
        tvHrv = findViewById(R.id.tv_hrv)
        tvLfPower = findViewById(R.id.tv_lf_power)
        tvHfPower = findViewById(R.id.tv_hf_power)

        // init services
        authService = AuthService()
        ablyService =
            AblyService("https://production-api25.become-hub.com/services/ably") { status ->
                runOnUiThread { tvStreaming.text = "Ably: $status" }
                Log.d(TAG, "🔵 Ably status: $status")
            }

        requestPermissionsIfNeeded()
        setupPolarCallback()
        startScan()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (req == PERMISSION_REQUEST_CODE && grants.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        }
    }

    private fun startScan() {
        Log.d(TAG, "🔍 Starting device scan")
        api.searchForDevice()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ info ->
                Log.d(TAG, "📡 Found: ${info.deviceId} (rssi=${info.rssi})")
                api.connectToDevice(info.deviceId)
            }, { err ->
                Log.e(TAG, "❌ Scan failed: $err")
            })
    }

    private fun setupPolarCallback() {
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                val msg = if (powered) "🟢 Bluetooth ON" else "🔴 Bluetooth OFF"
                Log.d(TAG, msg)
                runOnUiThread { tvBluetooth.text = msg }
            }

            override fun deviceConnected(info: PolarDeviceInfo) {
                Log.d(TAG, "✅ Connected: ${info.deviceId}")
                runOnUiThread { tvBluetooth.text = "Connected: ${info.deviceId}" }
                launchAuthAndStream(info.deviceId)
            }

            override fun deviceDisconnected(info: PolarDeviceInfo) {
                Log.d(TAG, "⚠️ Disconnected")
                ppiDisposable?.dispose()
                batteryDisposable?.dispose()
                ablyService.close()
            }

            override fun hrNotificationReceived(
                identifier: String,
                data: PolarHrData.PolarHrSample
            ) {

                val (lfPower, hfPower) = computeLfHf(ppiWindow)

                // 1) update UI heart rate
                runOnUiThread {
                    tvHeartRate.text = "HR: ${data.hr} BPM"
                    //   tvHrv.text       = "HRV: $rmssd ms"
                }


                // 4) send just the computed HRV (and HR) to Ably
                if (authToken.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        ablyService.sendHeartRate(
                            deviceCode = deviceCode,
                            userId = userId,
                            bpm = data.hr,
                            hrv = hrvValue,
                            lf = lfPower.toInt(),
                            hf = hfPower.toInt()
                        )
                    }
                }

            }


        })
    }

    private fun launchAuthAndStream(deviceId: String) {
        runOnUiThread { tvStreaming.text = "Streaming: CONNECTING" }
        CoroutineScope(Dispatchers.IO).launch {
            // get deviceCode
            if (authToken.isEmpty() || System.currentTimeMillis() / 1000 > expiresAt) {
                authService.startDeviceAuth()?.let {
                    code = it.code
                    deviceToken = it.deviceToken
                    expiresAt = it.expiresAt
                    Log.d(TAG, "🔑 Code=$code")
                    runOnUiThread { tvAuthCode.text = "Enter code on PC: $code" }
                }
            }
            // poll until OK
            while (true) {
                delay(5_000)
                authService.pollDeviceAuth(deviceToken)?.let { resp ->
                    if (resp.authenticated) {
                        authToken = resp.session
                        userId = resp.userId.toInt()
                        deviceCode = resp.deviceCode
                        Log.d(TAG, "🟢 Authenticated user=$userId")
                        ablyService.connectWithToken(authToken, userId, deviceCode)
                        runOnUiThread { tvStreaming.text = "Streaming: AUTHENTICATED" }
                        // start PPI stream
                        ppiDisposable?.dispose()
                        ppiDisposable = api.startPpiStreaming(deviceId)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe({ ppiData ->
                                ppiData.samples.forEach { sample ->
                                    val ppiMs = sample.ppi.toFloat()

                                    // drop implausible PPI values
                                    if (ppiMs < 300f || ppiMs > 2000f) return@forEach

                                    // update rolling window
                                    ppiWindow.add(ppiMs)
                                    if (ppiWindow.size > WINDOW_SIZE) ppiWindow.removeAt(0)

                                    // compute HRV only when buffer is full
                                    if (ppiWindow.size == WINDOW_SIZE) {
                                        // compute successive diffs
                                        val diffs = ppiWindow.zipWithNext { a, b -> b - a }
                                        // filter out large artefacts (>200ms)
                                        val validDiffs = diffs.filter { abs(it) < 200f }
                                        if (validDiffs.size >= 2) {
                                            val sq = validDiffs.map { it * it }
                                            val meanSq = sq.average()
                                            hrvValue = sqrt(meanSq).toInt()
                                        }
                                        tvHrv.text = "HRV: $hrvValue ms"

                                        val (lfPower, hfPower) = computeLfHf(ppiWindow)
                                        runOnUiThread {
                                            tvLfPower.text = "LF: ${"%.0f".format(lfPower)} ms²"
                                            tvHfPower.text = "HF: ${"%.0f".format(hfPower)} ms²"
                                        }
                                    }

                                    // instantaneous HR
                                    val hrFromPpi = (60000f / ppiMs).toInt()
                                    tvHeartRate.text = "HR: $hrFromPpi BPM"

                                    Log.d(TAG, "PPI: $ppiMs ms → HRV: $hrvValue ms")                          }
                            }, { err ->
                                Log.e(TAG, "PPI stream error: $err")
                            })
                        return@launch
                    } else {
                        Log.d(TAG, "⏳ Waiting for user auth…")
                    }
                }
            }

        }
    }


    private fun computeLfHf(ppiWindow: List<Float>, sampleRateHz: Double = 4.0): Pair<Double, Double> {
        // 1) Interpolate to an evenly sampled series at sampleRateHz
        val n = (ppiWindow.size.toDouble() * sampleRateHz / 1.0).toInt()
        val evenly = DoubleArray(n) { i ->
            // Simple linear interpolation: map i → index in ppiWindow
            val pos = i.toDouble() * (ppiWindow.size - 1) / (n - 1)
            val lo = pos.toInt()
            val hi = minOf(lo + 1, ppiWindow.lastIndex)
            val frac = pos - lo
            ppiWindow[lo] * (1 - frac) + ppiWindow[hi] * frac
        }

        // 2) Remove mean (detrend)
        val mean = evenly.average()
        for (i in evenly.indices) evenly[i] -= mean

        // 3) Zero‑pad to next power of two
        var fftSize = 1
        while (fftSize < n) fftSize = fftSize shl 1
        val fftInput = evenly.copyOf(fftSize)

        // 4) Run FFT
        val fft = DoubleFFT_1D(fftSize.toLong())
        fft.realForward(fftInput)

        // 5) Build PSD: power = (Re^2 + Im^2) / (fs * N)
        val df = sampleRateHz / fftSize
        val half = fftSize / 2
        val psd = DoubleArray(half) { k ->
            val re = fftInput[2*k]
            val im = fftInput[2*k + 1]
            (re*re + im*im) / (sampleRateHz * fftSize)
        }

        // 6) Integrate in LF and HF bands
        val lf = psd.withIndex()
            .filter { it.index * df in 0.04..0.15 }
            .sumOf { it.value * df }
        val hf = psd.withIndex()
            .filter { it.index * df in 0.15..0.4 }
            .sumOf { it.value * df }

        return Pair(lf, hf)
    }

    override fun onDestroy() {
        ppiDisposable?.dispose()
        batteryDisposable?.dispose()
        api.shutDown()
        super.onDestroy()
    }
}
