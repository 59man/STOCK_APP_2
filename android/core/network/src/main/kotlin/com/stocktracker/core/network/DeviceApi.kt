package com.stocktracker.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Path

// `platform` has no default: PersistJson doesn't set encodeDefaults, so a value equal to a
// default is silently omitted from the JSON body — the server's platform validation would then
// 400 on a body that's missing the field entirely. Forcing every caller to pass it explicitly
// guarantees it's always serialized.
@Serializable
data class DeviceHeartbeatBody(val id: String, val label: String, val platform: String)

@Serializable
data class DeviceAck(val ok: Boolean = false)

/** Matches `POST /api/devices/heartbeat` and `DELETE /api/devices/:id` in server/index.js. */
interface DeviceApi {
    @POST("api/devices/heartbeat")
    suspend fun heartbeat(@Body body: DeviceHeartbeatBody): DeviceAck

    @DELETE("api/devices/{id}")
    suspend fun delete(@Path("id") id: String): DeviceAck
}
