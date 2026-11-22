package com.firework.remote3000

import android.content.Context
import android.content.SharedPreferences

object RemotePrefs {
    private const val PREF_NAME = "remote_screen_3000_prefs"
    private const val KEY_CONTROL_ALLOWED = "control_allowed"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_PIN_REQUIRED = "pin_required"
    private const val KEY_SAVED_PIN = "saved_pin"
    private const val KEY_FLOATING_STOP = "floating_stop_button"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadIntoState(ctx: Context) {
        val p = prefs(ctx)
        ServiceState.controlAllowed.value = p.getBoolean(KEY_CONTROL_ALLOWED, true)
        ServiceState.keepScreenAwake.value = p.getBoolean(KEY_KEEP_AWAKE, true)
        ServiceState.showFloatingStopButton.value = p.getBoolean(KEY_FLOATING_STOP, true)
        ServiceState.pinRequired.value = p.getBoolean(KEY_PIN_REQUIRED, true)
        val savedPin = p.getString(KEY_SAVED_PIN, null)
        if (!savedPin.isNullOrBlank() && savedPin.length == 6) {
            ServiceState.pin.value = savedPin
        } else {
            val fresh = NetworkUtils.generatePin()
            ServiceState.pin.value = fresh
            p.edit().putString(KEY_SAVED_PIN, fresh).apply()
        }
    }

    fun setControlAllowed(ctx: Context, allowed: Boolean) {
        ServiceState.controlAllowed.value = allowed
        prefs(ctx).edit().putBoolean(KEY_CONTROL_ALLOWED, allowed).apply()
    }

    fun setKeepAwake(ctx: Context, awake: Boolean) {
        ServiceState.keepScreenAwake.value = awake
        prefs(ctx).edit().putBoolean(KEY_KEEP_AWAKE, awake).apply()
    }

    fun setShowFloatingStopButton(ctx: Context, show: Boolean) {
        ServiceState.showFloatingStopButton.value = show
        prefs(ctx).edit().putBoolean(KEY_FLOATING_STOP, show).apply()
    }

    fun setPinRequired(ctx: Context, required: Boolean) {
        ServiceState.pinRequired.value = required
        prefs(ctx).edit().putBoolean(KEY_PIN_REQUIRED, required).apply()
    }

    fun setPin(ctx: Context, newPin: String) {
        ServiceState.pin.value = newPin
        prefs(ctx).edit().putString(KEY_SAVED_PIN, newPin).apply()
    }
}
