package com.stocktracker.core.network

import com.stocktracker.core.model.InstrumentProfile
import com.stocktracker.core.model.TradingPeriod
import com.stocktracker.core.model.TradingPeriods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ---- chart meta (always available) ----

@Serializable
private data class ProfileChartResponse(val chart: ProfileChart? = null)
@Serializable
private data class ProfileChart(val result: List<ProfileResult>? = null)
@Serializable
private data class ProfileResult(val meta: ProfileMeta? = null)
@Serializable
private data class ProfileMeta(
    val currency: String? = null,
    val fullExchangeName: String? = null,
    val instrumentType: String? = null,
    val exchangeTimezoneName: String? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val regularMarketDayHigh: Double? = null,
    val regularMarketDayLow: Double? = null,
    val regularMarketVolume: Long? = null,
    val firstTradeDate: Long? = null,
    val currentTradingPeriod: CurrentTradingPeriod? = null,
)
@Serializable
private data class CurrentTradingPeriod(
    val pre: RawPeriod? = null,
    val regular: RawPeriod? = null,
    val post: RawPeriod? = null,
)
@Serializable
private data class RawPeriod(val start: Long? = null, val end: Long? = null)

// ---- quoteSummary (crumb-gated, best-effort) ----

/**
 * Yahoo wraps every number as `{"raw": 0.0015, "fmt": "0.15%"}`. Only [raw] is used — the app
 * formats values itself so a Yahoo locale change cannot leak into the UI.
 */
@Serializable
private data class YahooNum(val raw: Double? = null)

@Serializable
private data class QuoteSummaryResponse(val quoteSummary: QuoteSummary? = null)
@Serializable
private data class QuoteSummary(val result: List<SummaryResult>? = null)
@Serializable
private data class SummaryResult(
    val assetProfile: AssetProfile? = null,
    val fundProfile: FundProfile? = null,
)
@Serializable
private data class AssetProfile(
    val longBusinessSummary: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val website: String? = null,
    val country: String? = null,
)
@Serializable
private data class FundProfile(
    val family: String? = null,
    val legalType: String? = null,
    val categoryName: String? = null,
    val feesExpensesInvestment: FundFees? = null,
)
@Serializable
private data class FundFees(
    val annualReportExpenseRatio: YahooNum? = null,
    val totalNetAssets: YahooNum? = null,
)

/** The crumb-gated half of a profile, kept separate so a failure is representable as null. */
data class InstrumentProfileDetail(
    val description: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val website: String? = null,
    val country: String? = null,
    val fundFamily: String? = null,
    val legalType: String? = null,
    val expenseRatio: Double? = null,
    val totalNetAssets: Double? = null,
    val category: String? = null,
)

/** Null when the body is not a usable quoteSummary payload — an error envelope, or no result. */
fun parseQuoteSummary(body: String): InstrumentProfileDetail? {
    val result = runCatching {
        PersistJson.decodeFromString(QuoteSummaryResponse.serializer(), body)
    }.getOrNull()?.quoteSummary?.result?.firstOrNull() ?: return null
    val asset = result.assetProfile
    val fund = result.fundProfile
    return InstrumentProfileDetail(
        description = asset?.longBusinessSummary,
        sector = asset?.sector,
        industry = asset?.industry,
        website = asset?.website,
        country = asset?.country,
        fundFamily = fund?.family,
        legalType = fund?.legalType,
        expenseRatio = fund?.feesExpensesInvestment?.annualReportExpenseRatio?.raw,
        totalNetAssets = fund?.feesExpensesInvestment?.totalNetAssets?.raw,
        category = fund?.categoryName,
    )
}

/** Null when the chart response carries no result — a bad ticker, or an error envelope. */
fun parseProfileMeta(ticker: String, body: String): InstrumentProfile? {
    val meta = runCatching {
        PersistJson.decodeFromString(ProfileChartResponse.serializer(), body)
    }.getOrNull()?.chart?.result?.firstOrNull()?.meta ?: return null

    fun period(raw: RawPeriod?): TradingPeriod? {
        val start = raw?.start ?: return null
        val end = raw.end ?: return null
        return TradingPeriod(start, end)
    }

    val periods = meta.currentTradingPeriod?.let {
        TradingPeriods(pre = period(it.pre), regular = period(it.regular), post = period(it.post))
    }
    return InstrumentProfile(
        ticker = ticker,
        longName = meta.longName ?: meta.shortName,
        exchange = meta.fullExchangeName,
        instrumentType = meta.instrumentType,
        currency = meta.currency,
        exchangeTimeZone = meta.exchangeTimezoneName,
        tradingPeriods = periods,
        fiftyTwoWeekHigh = meta.fiftyTwoWeekHigh,
        fiftyTwoWeekLow = meta.fiftyTwoWeekLow,
        dayHigh = meta.regularMarketDayHigh,
        dayLow = meta.regularMarketDayLow,
        volume = meta.regularMarketVolume,
        firstTradeDate = meta.firstTradeDate,
    )
}

/** Merges the best-effort half in. A null [detail] leaves the profile exactly as it was. */
fun InstrumentProfile.withDetail(detail: InstrumentProfileDetail?): InstrumentProfile =
    if (detail == null) {
        this
    } else {
        copy(
            description = detail.description,
            sector = detail.sector,
            industry = detail.industry,
            website = detail.website,
            country = detail.country,
            fundFamily = detail.fundFamily,
            legalType = detail.legalType,
            expenseRatio = detail.expenseRatio,
            totalNetAssets = detail.totalNetAssets,
            category = detail.category,
        )
    }

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/**
 * Instrument metadata, direct from Yahoo like every other market-data client.
 *
 * Two calls. The chart-meta one always works and carries exchange, trading periods, the
 * 52-week range and the day range. The quoteSummary one carries the description, sector,
 * fund family, expense ratio and net assets, but only behind a cookie/crumb handshake that is
 * undocumented and can stop working without notice — so it is wrapped, its failure is logged
 * rather than surfaced, and every field it feeds is nullable.
 */
class InstrumentProfileClient(
    private val client: OkHttpClient = defaultClient,
    private val baseUrl: String = "https://query1.finance.yahoo.com",
    private val crumbProvider: CrumbProvider = CrumbProvider(client = client),
) {
    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(9, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .build()

        private const val MODULES = "assetProfile,fundProfile,summaryDetail,defaultKeyStatistics,quoteType"
    }

    suspend fun fetch(ticker: String): InstrumentProfile? {
        val meta = fetchMeta(ticker) ?: return null
        return meta.withDetail(runCatching { fetchDetail(ticker) }.getOrNull())
    }

    private suspend fun fetchMeta(ticker: String): InstrumentProfile? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v8/finance/chart/${encode(ticker)}?interval=1d&range=1d"
        val body = get(url) ?: return@withContext null
        parseProfileMeta(ticker, body)
    }

    /**
     * One retry only, and only on 401: that is the signature of a crumb that expired while
     * cached. A second 401 means the handshake itself has changed, which retrying cannot fix.
     */
    private suspend fun fetchDetail(ticker: String): InstrumentProfileDetail? = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val crumb = crumbProvider.crumb() ?: return@withContext null
            val url = "$baseUrl/v10/finance/quoteSummary/${encode(ticker)}" +
                "?modules=$MODULES&crumb=${encode(crumb)}"
            val request = Request.Builder().url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Cookie", crumbProvider.cookieHeader().orEmpty())
                .build()
            val outcome = client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> null
                    !response.isSuccessful -> return@withContext null
                    else -> response.body?.string()
                }
            }
            if (outcome != null) return@withContext parseQuoteSummary(outcome)
            if (attempt == 0) crumbProvider.invalidate()
        }
        null
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    /** Encoded exactly once — see the FX_CONVERTED_TICKERS gotcha in CLAUDE.md. */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
