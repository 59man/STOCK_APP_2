package com.stocktracker.core.data

import android.os.Build
import android.util.Log
import com.stocktracker.core.network.DeviceApi
import com.stocktracker.core.network.DeviceHeartbeatBody
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceRegistry"

/**
 * This device's registration with the server's device registry — see
 * docs/superpowers/specs/2026-08-29-device-registry-design.md. Every call here
 * is best-effort: a failed heartbeat or unregister is swallowed, never surfaced
 * to the user or allowed to block their actual data sync.
 */
@Singleton
class DeviceRegistry @Inject constructor(
    private val deviceApi: DeviceApi,
    private val settingsRepository: SettingsRepository,
) {
    private fun defaultLabel(): String = "Android · ${Build.MANUFACTURER} ${Build.MODEL}"

    /** Call after each successful pull/push cycle — piggybacks on the app's existing sync cadence. */
    suspend fun heartbeat() {
        val settings = settingsRepository.settings.first()
        if (settings.serverUrl.isBlank() || settings.apiKey.isBlank()) return
        runCatching {
            val id = settingsRepository.getOrCreateDeviceId()
            deviceApi.heartbeat(DeviceHeartbeatBody(id = id, label = defaultLabel(), platform = "android"))
        }.onFailure { Log.w(TAG, "heartbeat failed (best-effort, swallowed)", it) }
    }

    /**
     * Best-effort removes this device's row from the server, then clears the
     * stored device id so a future reconnect registers fresh. Does NOT clear
     * the server URL/API key — that's the caller's job (Settings' Disconnect
     * button), kept separate so this class stays registry-only.
     */
    suspend fun unregister() {
        val settings = settingsRepository.settings.first()
        if (settings.serverUrl.isNotBlank() && settings.apiKey.isNotBlank()) {
            runCatching {
                val id = settingsRepository.getOrCreateDeviceId()
                deviceApi.delete(id)
            }.onFailure { Log.w(TAG, "unregister failed (best-effort, swallowed)", it) }
        }
        settingsRepository.clearDeviceId()
    }
}
