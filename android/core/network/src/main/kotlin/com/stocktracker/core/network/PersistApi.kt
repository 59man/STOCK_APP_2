package com.stocktracker.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class PersistResponse(val value: String? = null)

@Serializable
data class PersistBody(val value: String)

@Serializable
data class PersistAck(val ok: Boolean = false)

/**
 * Matches `GET/POST /api/persist/:key` in server/index.js exactly. The
 * `value` field is itself a JSON-encoded string — callers must
 * `Json.encodeToString` their payload before wrapping it in [PersistBody],
 * and `Json.decodeFromString` [PersistResponse.value] again after reading.
 * See PersistKeys.kt for the encode/decode helpers that do this safely.
 */
interface PersistApi {
    @GET("api/persist/{key}")
    suspend fun get(@Path("key") key: String): PersistResponse

    @POST("api/persist/{key}")
    suspend fun set(@Path("key") key: String, @Body body: PersistBody): PersistAck
}
