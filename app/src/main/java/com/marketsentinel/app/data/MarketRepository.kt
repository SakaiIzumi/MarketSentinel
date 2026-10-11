package com.marketsentinel.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public data only; tries alternate public endpoints to reduce transient failures. */
class MarketRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun snapshot(rawSymbol: String): Result<MarketSnapshot> = runCatching {
        val symbol = rawSymbol.trim().uppercase()
        require(symbol.matches(Regex("[A-Z0-9.^=_-]{1,24}"))) { "标的代码格式不正确：" + rawSymbol }
        if (isCryptoSymbol(symbol)) crypto(symbol) else yahoo(symbol)
    }

    private fun isCryptoSymbol(symbol: String): Boolean =
        symbol.matches(Regex("[A-Z0-9]{5,24}")) &&
            listOf("USDT", "USDC", "BUSD", "FDUSD", "BTC", "ETH").any { symbol.endsWith(it) }

    private fun crypto(symbol: String): MarketSnapshot {
        val hosts = listOf("https://api.binance.com", "https://data-api.binance.vision", "https://api1.binance.com")
        val ticker = jsonFromHosts(hosts) { it + "/api/v3/ticker/24hr?symbol=" + symbol }
        val price = ticker.optString("lastPrice").toDoubleOrNull() ?: error("Binance 未返回有效最新价")
        val change = ticker.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
        val volume = ticker.optString("quoteVolume").toDoubleOrNull() ?: 0.0
        val rows = arrayFromHosts(hosts) { it + "/api/v3/klines?symbol=" + symbol + "&interval=15m&limit=100" }
        val candles = (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONArray(i) ?: return@mapNotNull null
            if (r.length() < 6) return@mapNotNull null
            val o = r.optString(1).toDoubleOrNull() ?: return@mapNotNull null
            val h = r.optString(2).toDoubleOrNull() ?: return@mapNotNull null
            val l = r.optString(3).toDoubleOrNull() ?: return@mapNotNull null
            val c = r.optString(4).toDoubleOrNull() ?: return@mapNotNull null
            Candle(r.optLong(0), o, h, l, c, r.optString(5).toDoubleOrNull() ?: 0.0)
        }
        require(price > 0 && price.isFinite()) { "Binance 返回无效价格" }
        require(candles.size >= 26) { "Binance K 线不足，无法计算技术指标" }
        return MarketSnapshot(
            MarketQuote(symbol, "CRYPTO", price, change, volume, source = "Binance 公共现货 API · 15m"),
            candles
        )
    }

    private fun yahoo(symbol: String): MarketSnapshot {
        val hosts = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")
        val variants = listOf("5d" to "15m", "1d" to "5m", "1mo" to "1h")
        var lastError: Throwable? = null
        for ((range, interval) in variants) {
            try {
                val root = jsonFromHosts(hosts) {
                    it + "/v8/finance/chart/" + symbol + "?range=" + range + "&interval=" + interval + "&includePrePost=false"
                }
                val chart = root.optJSONObject("chart") ?: error("Yahoo Finance 响应缺少 chart")
                chart.optJSONObject("error")?.let {
                    if (!it.isNull("description")) error(it.optString("description"))
                }
                val result = chart.optJSONArray("result")?.optJSONObject(0) ?: error("Yahoo Finance 未返回 " + symbol)
                val meta = result.optJSONObject("meta") ?: JSONObject()
                val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
                    ?: error("Yahoo Finance 缺少 K 线")
                val ts = result.optJSONArray("timestamp") ?: JSONArray()
                val opens = quote.optJSONArray("open") ?: JSONArray()
                val highs = quote.optJSONArray("high") ?: JSONArray()
                val lows = quote.optJSONArray("low") ?: JSONArray()
                val closes = quote.optJSONArray("close") ?: JSONArray()
                val volumes = quote.optJSONArray("volume") ?: JSONArray()
                val candles = (0 until minOf(ts.length(), closes.length())).mapNotNull { i ->
                    val c = number(closes, i) ?: return@mapNotNull null
                    val o = number(opens, i) ?: c
                    Candle(ts.optLong(i) * 1000, o, number(highs, i) ?: maxOf(o, c),
                        number(lows, i) ?: minOf(o, c), c, number(volumes, i) ?: 0.0)
                }
                val last = candles.lastOrNull() ?: error("没有可用 K 线：" + symbol)
                val price = meta.optDouble("regularMarketPrice", Double.NaN)
                    .takeIf { it.isFinite() && it > 0 } ?: last.close
                val previous = meta.optDouble("chartPreviousClose", Double.NaN)
                    .takeIf { it.isFinite() && it > 0 }
                    ?: meta.optDouble("previousClose", Double.NaN).takeIf { it.isFinite() && it > 0 }
                    ?: candles.dropLast(1).lastOrNull()?.close ?: price
                val market = when {
                    symbol.endsWith(".SS") || symbol.endsWith(".SZ") -> "A股"
                    symbol.endsWith(".HK") || symbol == "^HSI" -> "港股/恒指"
                    symbol.startsWith("^") -> "海外指数"
                    else -> "美股/ETF"
                }
                return MarketSnapshot(
                    MarketQuote(symbol, market, price,
                        if (previous > 0) (price - previous) / previous * 100 else 0.0,
                        last.volume, source = "Yahoo Finance · " + interval),
                    candles
                )
            } catch (e: Exception) {
                lastError = e
                val message = e.message.orEmpty()
                if (message.contains("HTTP 429") || message.contains("HTTP 403") || message.contains("HTTP 401")) break
            }
        }
        throw IllegalStateException("Yahoo Finance 获取 " + symbol +
            " 失败（已尝试双域名及 15m/5m/1h 周期）：" + (lastError?.message ?: "未知错误"))
    }

    private fun jsonFromHosts(hosts: List<String>, url: (String) -> String): JSONObject {
        var lastError: Throwable? = null
        for (host in hosts) {
            try { return json(url(host)) } catch (e: Exception) { lastError = e }
        }
        throw IllegalStateException("所有行情接口均失败：" + (lastError?.message ?: "网络异常"))
    }

    private fun arrayFromHosts(hosts: List<String>, url: (String) -> String): JSONArray {
        var lastError: Throwable? = null
        for (host in hosts) {
            try { return array(url(host)) } catch (e: Exception) { lastError = e }
        }
        throw IllegalStateException("所有 K 线接口均失败：" + (lastError?.message ?: "网络异常"))
    }

    private fun json(url: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .header("Referer", "https://finance.yahoo.com/")
            .get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP " + res.code + " (" + res.request.url.host + ")")
            return JSONObject(res.body?.string() ?: error("行情响应为空"))
        }
    }

    private fun array(url: String): JSONArray {
        val req = Request.Builder().url(url)
            .header("User-Agent", "MarketSentinel-Android/0.3")
            .header("Accept", "application/json")
            .get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP " + res.code + " (" + res.request.url.host + ")")
            return JSONArray(res.body?.string() ?: error("K 线响应为空"))
        }
    }

    private fun number(a: JSONArray, i: Int): Double? =
        if (i !in 0 until a.length() || a.isNull(i)) null
        else a.optString(i).toDoubleOrNull()?.takeIf { it.isFinite() }
}
