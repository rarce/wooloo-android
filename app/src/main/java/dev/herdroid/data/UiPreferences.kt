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

    /** Mirrors herdr's `ui.status_indicators = "symbols"`: tell states apart by shape too. */
    private val _statusSymbols = MutableStateFlow(prefs.getBoolean(KEY_STATUS_SYMBOLS, false))
    val statusSymbols: StateFlow<Boolean> = _statusSymbols.asStateFlow()

    /**
     * Claude Code and Codex quotas on the spaces screen. Off until the user turns it on, since it
     * reads the agents' sign-ins on the host and sends their tokens to Anthropic and OpenAI.
     */
    private val _agentQuotas = MutableStateFlow(prefs.getBoolean(KEY_AGENT_QUOTAS, false))
    val agentQuotas: StateFlow<Boolean> = _agentQuotas.asStateFlow()

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

    fun setStatusSymbols(on: Boolean) {
        _statusSymbols.value = on
        prefs.edit().putBoolean(KEY_STATUS_SYMBOLS, on).apply()
    }

    fun setAgentQuotas(on: Boolean) {
        _agentQuotas.value = on
        prefs.edit().putBoolean(KEY_AGENT_QUOTAS, on).apply()
    }

    private companion object {
        const val KEY_FONT = "font_size"
        const val KEY_SCREEN_ON = "keep_screen_on"
        const val KEY_HAPTICS = "haptics"
        const val KEY_STATUS_SYMBOLS = "status_symbols"
        const val KEY_AGENT_QUOTAS = "agent_quotas"
    }
}
