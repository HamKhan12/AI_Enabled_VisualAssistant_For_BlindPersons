package com.example.smartassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * SIMPLIFIED Battery Optimizer
 *
 * Features:
 * - At ≤ 20%: Voice alert "Battery low" (once with cooldown)
 * - At ≤ 15%: Voice alert + Dim screen + Disable GPT (YOLO only)
 * - > 20%: Normal operation, restore brightness, enable GPT
 *
 * NO Power Saver Mode, NO YOLO size changes, NO capture interval changes
 */
class BatteryOptimizer(
    private val context: Context,
    private val listener: BatteryEventListener?
) {

    companion object {
        private const val TAG = "BatteryOptimizer"
    }

    var currentBatteryLevel = 100
        private set
    var isCharging = false
        private set

    // Alert cooldown to prevent spam (1 minute)
    private var lastAlertTime = 0L
    private val ALERT_COOLDOWN_MS = 60000L // 1 minute

    // Track if we've already alerted for current low battery state
    private var hasAlertedForLowBattery = false
    private var hasAlertedForCriticalBattery = false

    // Brightness tracking
    private var originalBrightness = -1f

    private var batteryReceiver: BatteryReceiver? = null
    private var isRegistered = false

    interface BatteryEventListener {
        fun onLowBattery(level: Int)      // ≤ 20%
        fun onCriticalBattery(level: Int) // ≤ 15%
        fun onBatteryHealthy(level: Int)  // > 20%
        fun onChargingStarted()
        fun onChargingStopped()
    }

    init {
        registerBatteryReceiver()
        getInitialBatteryStatus()
    }

    private fun getInitialBatteryStatus() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            currentBatteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            isCharging = batteryManager.isCharging
            Log.d(TAG, "Initial battery status: $currentBatteryLevel%, Charging: $isCharging")
        }
        checkBatteryLevel(currentBatteryLevel, isCharging)
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = BatteryReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        try {
            context.registerReceiver(batteryReceiver, filter)
            isRegistered = true
            Log.d(TAG, "Battery receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }

    fun unregisterReceiver() {
        if (isRegistered && batteryReceiver != null) {
            try {
                context.unregisterReceiver(batteryReceiver)
                isRegistered = false
                Log.d(TAG, "Battery receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister battery receiver: ${e.message}")
            }
        }
    }

    private var lastProcessingTime = 0L
    private val PROCESSING_COOLDOWN_MS = 1000

    private fun checkBatteryLevel(level: Int, charging: Boolean) {
        // Throttle processing - prevent duplicate rapid calls
        val now = System.currentTimeMillis()
        if (now - lastProcessingTime < PROCESSING_COOLDOWN_MS) {
            Log.d(TAG, "Throttling battery check - too frequent")
            return
        }
        lastProcessingTime = now

        Log.d(TAG, "checkBatteryLevel: level=$level, charging=$charging")

        // If charging, restore everything and skip alerts
        if (charging) {
            // Reset alert flags
            hasAlertedForLowBattery = false
            hasAlertedForCriticalBattery = false

            // Restore brightness if dimmed
            if (originalBrightness != -1f) {
                restoreScreenBrightness()
            }
        }

        when {
            // CRITICAL BATTERY (≤ 15%)
            level <= 15 -> {
                if (!hasAlertedForCriticalBattery && shouldTriggerAlert()) {
                    listener?.onCriticalBattery(level)
                    hasAlertedForCriticalBattery = true
                    hasAlertedForLowBattery = true // Mark low as also triggered
                    lastAlertTime = System.currentTimeMillis()
                    Log.w(TAG, "⚠️ CRITICAL BATTERY: $level%")
                }
                // Dim screen only once
                if (originalBrightness == -1f) {
                    dimScreen()
                }
            }

            // LOW BATTERY (≤ 20%) - Only alert, no other changes
            level <= 20 -> {
                if (!hasAlertedForLowBattery && shouldTriggerAlert()) {
                    listener?.onLowBattery(level)
                    hasAlertedForLowBattery = true
                    lastAlertTime = System.currentTimeMillis()
                    Log.w(TAG, "⚠️ LOW BATTERY: $level%")
                }
                // Don't disable GPT or dim screen until ≤ 15%
            }

            // BATTERY HEALTHY (> 20%)
            level > 20 -> {
                // Reset alert flags so user gets alert again if battery drops
                hasAlertedForLowBattery = false
                hasAlertedForCriticalBattery = false

                // Restore brightness if dimmed
                if (originalBrightness != -1f) {
                    restoreScreenBrightness()
                }

                listener?.onBatteryHealthy(level)
                Log.d(TAG, "✅ Battery healthy: $level%")
            }
        }
    }

    private fun shouldTriggerAlert(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < ALERT_COOLDOWN_MS) {
            Log.d(TAG, "⏭️ Alert cooldown active - skipping duplicate alert")
            return false
        }
        return true
    }

    private fun dimScreen() {
        try {
            val activity = context as? AppCompatActivity
            activity?.let {
                it.runOnUiThread {
                    val window = it.window
                    if (window != null) {
                        // Store original brightness
                        val currentBrightness = window.attributes.screenBrightness
                        originalBrightness = if (currentBrightness >= 0) currentBrightness else -1f
                        Log.d(TAG, "Stored original brightness: $originalBrightness")

                        // Dim to 60%
                        val params = window.attributes
                        params.screenBrightness = 0.6f
                        window.attributes = params
                        Log.d(TAG, "✅ Screen dimmed to 60%")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dim screen: ${e.message}")
        }
    }

    private fun restoreScreenBrightness() {
        try {
            val activity = context as? AppCompatActivity
            activity?.let {
                it.runOnUiThread {
                    val window = it.window
                    if (window != null) {
                        val params = window.attributes
                        if (originalBrightness != -1f && originalBrightness >= 0) {
                            params.screenBrightness = originalBrightness
                            Log.d(TAG, "✅ Screen brightness restored to $originalBrightness")
                        } else {
                            params.screenBrightness = -1f
                            Log.d(TAG, "✅ Screen brightness reset to system default")
                        }
                        window.attributes = params
                        originalBrightness = -1f
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore brightness: ${e.message}")
        }
    }

    inner class BatteryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = if (scale > 0) (level * 100 / scale) else -1
                    val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

                    if (batteryPct != -1) {
                        currentBatteryLevel = batteryPct
                        isCharging = charging
                        checkBatteryLevel(batteryPct, charging)
                    }
                }

                Intent.ACTION_BATTERY_LOW -> {
                    if (!isCharging && shouldTriggerAlert() && !hasAlertedForLowBattery) {
                        listener?.onLowBattery(currentBatteryLevel)
                        hasAlertedForLowBattery = true
                        lastAlertTime = System.currentTimeMillis()
                        Log.w(TAG, "📱 Battery LOW broadcast received")
                    }
                }

                Intent.ACTION_BATTERY_OKAY -> {
                    Log.d(TAG, "✅ Battery OKAY broadcast received")
                    if (originalBrightness != -1f) {
                        restoreScreenBrightness()
                    }
                }

                Intent.ACTION_POWER_CONNECTED -> {
                    if (!isCharging) {
                        isCharging = true
                        listener?.onChargingStarted()
                        Log.d(TAG, "🔌 Power connected - charging started")

                        // Reset alert flags
                        hasAlertedForLowBattery = false
                        hasAlertedForCriticalBattery = false
                        lastAlertTime = 0L

                        // Restore brightness and GPT
                        if (originalBrightness != -1f) {
                            restoreScreenBrightness()
                        }
                    }
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (isCharging) {
                        isCharging = false
                        listener?.onChargingStopped()
                        Log.d(TAG, "🔌 Power disconnected - charging stopped")

                        // Reset alert flags so user gets alert again if battery is still low
                        hasAlertedForLowBattery = false
                        hasAlertedForCriticalBattery = false
                        lastAlertTime = 0L
                        checkBatteryLevel(currentBatteryLevel, false)
                    }
                }
            }
        }
    }
}