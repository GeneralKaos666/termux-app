package com.termux.app.data.wakelock

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.termux.shared.android.PermissionUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants

open class WakeLockController @JvmOverloads constructor(
    private val context: Context,
    private val onChange: () -> Unit,
    private val requestBatteryOptimizationExemption: () -> Unit = {
        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(context)) {
            PermissionUtils.requestDisableBatteryOptimizations(context)
        }
    },
) {
    private val logTag = "TermuxService"

    /** The wake lock and wifi lock are always acquired and released together. */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    protected var locksHeld: Boolean = false

    open val isHeld: Boolean
        get() = locksHeld

    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint("WakelockTimeout", "BatteryLife")
    open fun acquire() {
        if (locksHeld) {
            Logger.logDebug(logTag, "Ignoring acquiring WakeLocks since they are already held")
            return
        }

        Logger.logDebug(logTag, "Acquiring WakeLocks")

        acquirePlatformLocks()
        locksHeld = true

        requestBatteryOptimizationExemption()

        onChange()

        Logger.logDebug(logTag, "WakeLocks acquired successfully")
    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    open fun release(updateNotification: Boolean) {
        if (!locksHeld) {
            Logger.logDebug(logTag, "Ignoring releasing WakeLocks since none are already held")
            return
        }

        Logger.logDebug(logTag, "Releasing WakeLocks")

        releasePlatformLocks()
        locksHeld = false

        if (updateNotification)
            onChange()

        Logger.logDebug(logTag, "WakeLocks released successfully")
    }

    @SuppressLint("WakelockTimeout")
    protected open fun acquirePlatformLocks() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            TermuxConstants.TERMUX_APP_NAME.lowercase() + ":service-wakelock"
        )
        wakeLock!!.acquire()

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            TermuxConstants.TERMUX_APP_NAME.lowercase()
        )
        wifiLock!!.acquire()
    }

    protected open fun releasePlatformLocks() {
        if (wakeLock != null) {
            wakeLock!!.release()
            wakeLock = null
        }

        if (wifiLock != null) {
            wifiLock!!.release()
            wifiLock = null
        }
    }
}
