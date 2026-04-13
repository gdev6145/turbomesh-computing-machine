package com.turbomesh.computingmachine.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists mesh network settings in SharedPreferences and exposes them
 * as a [StateFlow] so observers react to runtime changes.
 */
class MeshSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<MeshSettings> = _settings.asStateFlow()

    fun update(settings: MeshSettings) {
        prefs.edit().apply {
            putInt(KEY_TTL, settings.defaultTtl)
            putLong(KEY_HEARTBEAT_MS, settings.heartbeatIntervalMs)
            putInt(KEY_MAX_RECONNECT, settings.maxReconnectAttempts)
            putInt(KEY_RETRIES, settings.messageRetries)
            putString(KEY_THEME, settings.themeMode)
            putBoolean(KEY_ENCRYPTION, settings.encryptionEnabled)
            putBoolean(KEY_BRIDGE_ENABLED, settings.bridgeEnabled)
            putString(KEY_BRIDGE_URL, settings.bridgeServerUrl)
            putBoolean(KEY_PROXIMITY_ENABLED, settings.proximityAlertsEnabled)
            putInt(KEY_PROXIMITY_THRESHOLD, settings.proximityAlertThresholdDbm)
            apply()
        }
        _settings.value = settings
    }

    fun current(): MeshSettings = _settings.value

    private fun load() = MeshSettings(
        defaultTtl = prefs.getInt(KEY_TTL, MeshSettings.DEFAULT_TTL),
        heartbeatIntervalMs = prefs.getLong(KEY_HEARTBEAT_MS, MeshSettings.DEFAULT_HEARTBEAT_MS),
        maxReconnectAttempts = prefs.getInt(KEY_MAX_RECONNECT, MeshSettings.DEFAULT_MAX_RECONNECT),
        messageRetries = prefs.getInt(KEY_RETRIES, MeshSettings.DEFAULT_RETRIES),
        themeMode = prefs.getString(KEY_THEME, MeshSettings.THEME_SYSTEM) ?: MeshSettings.THEME_SYSTEM,
        encryptionEnabled = prefs.getBoolean(KEY_ENCRYPTION, false),
        bridgeEnabled = prefs.getBoolean(KEY_BRIDGE_ENABLED, false),
        bridgeServerUrl = prefs.getString(KEY_BRIDGE_URL, "") ?: "",
        proximityAlertsEnabled = prefs.getBoolean(KEY_PROXIMITY_ENABLED, false),
        proximityAlertThresholdDbm = prefs.getInt(KEY_PROXIMITY_THRESHOLD, -75),
    )

    companion object {
        private const val PREFS_NAME = "mesh_settings"
        private const val KEY_TTL = "ttl"
        private const val KEY_HEARTBEAT_MS = "heartbeat_ms"
        private const val KEY_MAX_RECONNECT = "max_reconnect"
        private const val KEY_RETRIES = "retries"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ENCRYPTION = "encryption_enabled"
        private const val KEY_BRIDGE_ENABLED = "bridge_enabled"
        private const val KEY_BRIDGE_URL = "bridge_url"
        private const val KEY_PROXIMITY_ENABLED = "proximity_enabled"
        private const val KEY_PROXIMITY_THRESHOLD = "proximity_threshold"
    }
}

data class MeshSettings(
    val defaultTtl: Int,
    val heartbeatIntervalMs: Long,
    val maxReconnectAttempts: Int,
    val messageRetries: Int,
    /** One of [THEME_SYSTEM], [THEME_LIGHT], [THEME_DARK]. */
    val themeMode: String = THEME_SYSTEM,
    /** Enable AES-256-GCM end-to-end encryption for DATA messages (feature 11). */
    val encryptionEnabled: Boolean = false,
    /** Enable the WebSocket relay bridge (feature 15). */
    val bridgeEnabled: Boolean = false,
    /** URL of the WebSocket relay server (feature 15). */
    val bridgeServerUrl: String = "",
    /** dBm threshold for proximity alerts (feature 25). */
    val proximityAlertThresholdDbm: Int = -75,
    /** Whether proximity alerts are enabled (feature 25). */
    val proximityAlertsEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_TTL = 7
        const val DEFAULT_HEARTBEAT_MS = 10_000L
        const val DEFAULT_MAX_RECONNECT = 5
        const val DEFAULT_RETRIES = 3

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        val DEFAULT = MeshSettings(
            defaultTtl = DEFAULT_TTL,
            heartbeatIntervalMs = DEFAULT_HEARTBEAT_MS,
            maxReconnectAttempts = DEFAULT_MAX_RECONNECT,
            messageRetries = DEFAULT_RETRIES,
            themeMode = THEME_SYSTEM
        )
    }
}
