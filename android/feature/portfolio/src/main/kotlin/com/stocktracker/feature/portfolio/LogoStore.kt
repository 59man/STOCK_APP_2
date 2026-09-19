package com.stocktracker.feature.portfolio

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

private const val LOGO_SIZE_PX = 128

/**
 * Per-install logo overrides in app-private storage. A file here beats every remote source,
 * so it is how a ticker no logo service covers (CSG, the Japanese banks) gets an image at all,
 * and how any ticker gets corrected by hand.
 *
 * Not synced to the server: these are binary blobs, and the persist store holds one JSON value
 * per key. A reinstall therefore falls back to the bundled assets and the remote chain.
 */
class LogoStore(private val filesDir: File) {
    private val dir: File get() = File(filesDir, "logos")

    /**
     * Tickers come out of imported broker statements, so the name is sanitised rather than
     * trusted — a "../" in a ticker would otherwise write outside app-private storage.
     */
    fun fileFor(ticker: String): File {
        val safe = ticker.uppercase().replace(Regex("[^A-Z0-9.\\-]"), "_")
        return File(dir, "$safe.png")
    }

    fun has(ticker: String): Boolean = fileFor(ticker).exists()

    fun clear(ticker: String) {
        fileFor(ticker).delete()
    }

    /**
     * Downscales before writing: a picked phone photo is several megabytes, and the list
     * renders this file on every row.
     */
    suspend fun save(ticker: String, source: InputStream): Unit = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val decoded = source.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Picked file is not a decodable image")
        val scaled = Bitmap.createScaledBitmap(decoded, LOGO_SIZE_PX, LOGO_SIZE_PX, true)
        fileFor(ticker).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Copies bundled assets in on first run. Never overwrites, so a user-set file survives. */
    suspend fun seedFromAssets(assets: AssetManager): Unit = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val names = runCatching { assets.list("logos").orEmpty() }.getOrDefault(emptyArray())
        names.filter { it.endsWith(".png") }.forEach { name ->
            val target = File(dir, name)
            if (!target.exists()) {
                runCatching {
                    assets.open("logos/$name").use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }
}
