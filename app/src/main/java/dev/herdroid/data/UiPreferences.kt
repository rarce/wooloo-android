package dev.herdroid.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small per-device UI preferences. */
class UiPreferences(private val prefs: SharedPreferences) {
    private val _fontSize = MutableStateFlow(prefs.getFloat(KEY_FONT, 12.5f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_ON, true))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _haptics = MutableStateFlow(prefs.getBoolean(KEY_HAPTICS, true))
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    fun setFontSize(sp: Float) {
        _fontSize.value = sp
        prefs.edit().putFloat(KEY_FONT, sp).apply()
    }

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        prefs.edit().putBoolean(KEY_SCREEN_ON, on).apply()
    }

    fun setHaptics(on: Boolean) {
        _haptics.value = on
        prefs.edit().putBoolean(KEY_HAPTICS, on).apply()
    }

    private companion object {
        const val KEY_FONT = "font_size"
        const val KEY_SCREEN_ON = "keep_screen_on"
        const val KEY_HAPTICS = "haptics"
    }
}
