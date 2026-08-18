package com.stocktracker.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

/**
 * Server URL and API key are user Settings, not build constants — this reads
 * the current value on every request so a change in Settings takes effect
 * immediately, no app restart or DI-graph rebuild needed.
 */
interface PersistApiConfig {
    suspend fun serverUrl(): String
    suspend fun apiKey(): String
}

private class RuntimeServerInterceptor(private val config: PersistApiConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val serverUrl = runBlocking { config.serverUrl() }
        val target = serverUrl.toHttpUrlOrNull()
            ?: return chain.proceed(original) // malformed setting — let it fail downstream, visible to the user

        val rewritten = original.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}

private class ApiKeyInterceptor(private val config: PersistApiConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = runBlocking { config.apiKey() }
        val request = chain.request().newBuilder()
            .header("X-API-Key", key)
            .build()
        return chain.proceed(request)
    }
}

fun createPersistApi(config: PersistApiConfig): PersistApi {
    val client = OkHttpClient.Builder()
        .addInterceptor(RuntimeServerInterceptor(config))
        .addInterceptor(ApiKeyInterceptor(config))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // baseUrl is a placeholder — RuntimeServerInterceptor overwrites scheme/host/port
    // on every request, but Retrofit still requires a syntactically valid one up front.
    val retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(PersistJson.asConverterFactory("application/json".toMediaType()))
        .build()

    return retrofit.create(PersistApi::class.java)
}
