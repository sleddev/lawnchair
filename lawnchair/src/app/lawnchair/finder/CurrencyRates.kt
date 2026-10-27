package app.lawnchair.finder

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Exchange rates for the finder's currency conversions, relative to USD. They're fetched from a
 * free, keyless API and cached on disk for [MAX_AGE_MS], so conversions also work offline with
 * the last known rates.
 */
object CurrencyRates {

    private const val TAG = "CurrencyRates"
    private const val URL_LATEST = "https://open.er-api.com/v6/latest/USD"
    private const val PREFS = "finder_currency_rates"
    private const val KEY_JSON = "rates"
    private const val KEY_TIME = "fetched_at"
    private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(12)

    private val mutex = Mutex()

    @Volatile
    private var cached: Map<String, Double>? = null

    @Volatile
    private var cachedAt = 0L

    /** The cached rates, without hitting the network. */
    fun peek(context: Context): Map<String, Double>? {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return parse(json)?.also {
            cached = it
            cachedAt = prefs.getLong(KEY_TIME, 0)
        }
    }

    /** Returns up to date rates, fetching them if the cache is missing or stale. */
    suspend fun get(context: Context): Map<String, Double>? = mutex.withLock {
        val current = peek(context)
        if (current != null && System.currentTimeMillis() - cachedAt < MAX_AGE_MS) {
            return@withLock current
        }
        fetch(context) ?: current
    }

    private suspend fun fetch(context: Context): Map<String, Double>? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(URL_LATEST).openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val rates = parse(body) ?: return@withContext null
                val now = System.currentTimeMillis()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_JSON, body)
                    .putLong(KEY_TIME, now)
                    .apply()
                cached = rates
                cachedAt = now
                rates
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't fetch exchange rates", e)
            null
        }
    }

    private fun parse(json: String): Map<String, Double>? = try {
        val rates = JSONObject(json).getJSONObject("rates")
        buildMap {
            rates.keys().forEach { code -> put(code, rates.getDouble(code)) }
        }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
