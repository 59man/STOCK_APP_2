package com.stocktracker.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The Settings screen's "Test connection". Makes a real authenticated read rather than hitting the
 * unauthenticated `/api/health`, which reports "Connected" with a wrong API key.
 */
object HealthCheck {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun check(serverUrl: String, apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = serverUrl.trimEnd('/') + "/api/persist/" + PersistKeys.PORTFOLIOS
            val request = Request.Builder().url(url).header("X-API-Key", apiKey).get().build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.success(Unit)
                    response.code == 401 -> Result.failure(Exception("server reached, but the API key is wrong"))
                    else -> Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
