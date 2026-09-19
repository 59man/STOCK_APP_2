package com.stocktracker.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private const val CRUMB_TTL_MS = 60 * 60 * 1000L

/**
 * Obtains the session cookie and crumb that Yahoo's `quoteSummary` requires. Called plainly
 * that endpoint answers `401 {"error":{"description":"Invalid Crumb"}}`; with a cookie from
 * `fc.yahoo.com` and the crumb minted against it, it answers normally.
 *
 * This is undocumented and may stop working at any time, which is why every caller treats a
 * null crumb as "these fields are unavailable" rather than an error.
 *
 * @param cookieUrl the endpoint whose only job is to set a cookie. It answers **404** while
 *   still setting it, so its status code is deliberately ignored.
 */
class CrumbProvider(
    private val client: OkHttpClient,
    private val cookieUrl: String = "https://fc.yahoo.com/",
    private val crumbUrl: String = "https://query1.finance.yahoo.com/v1/test/getcrumb",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cachedCrumb: String? = null
    private var cachedCookie: String? = null
    private var fetchedAt = 0L

    fun cookieHeader(): String? = cachedCookie

    fun invalidate() {
        cachedCrumb = null
        cachedCookie = null
        fetchedAt = 0L
    }

    /**
     * The mutex means a screen opening several positions at once performs one handshake rather
     * than racing five, which Yahoo would answer with rate limiting.
     */
    suspend fun crumb(): String? = mutex.withLock {
        val cached = cachedCrumb
        if (cached != null && now() - fetchedAt < CRUMB_TTL_MS) return@withLock cached
        handshake()
    }

    private suspend fun handshake(): String? = withContext(Dispatchers.IO) {
        val cookie = runCatching {
            val request = Request.Builder().url(cookieUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                response.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').takeIf(String::isNotBlank) }
                    .joinToString("; ")
                    .takeIf { it.isNotBlank() }
            }
        }.getOrNull() ?: return@withContext null

        val crumb = runCatching {
            val request = Request.Builder().url(crumbUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Cookie", cookie)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()?.trim()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null

        cachedCookie = cookie
        cachedCrumb = crumb
        fetchedAt = now()
        crumb
    }
}
