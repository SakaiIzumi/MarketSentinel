package com.marketsentinel.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public market-data only. Yahoo Finance chart endpoint is unofficial and may be delayed or limited. */
class MarketRepository {
    private val client = OkHttpClient.Builder().connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

    fun snapshot(rawSymbol: String): Result<MarketSnapshot> = runCatching {
        val symbol = rawSymbol.trim().uppercase()
        require(symbol.matches(Regex("[A-Z0-9.^=_-]{1,24}"))) { "标的代码格式不正确：$rawSymbol" }
        if (symbol.matches(Regex("[A-Z0-9]{5,20}")) &&
            listOf("USDT", "USDC", "BUSD", "BTC", "ETH", "FDUSD").any { symbol.endsWith(it) }) crypto(symbol)
        else yahoo(symbol)
    }

    private fun crypto(symbol: String): MarketSnapshot {
        val ticker = json("https://data-api.binance.vision/api/v3/ticker/24hr?symbol=$symbol")
        val price = ticker.getString("lastPrice").toDouble()
        val change = ticker.getString("priceChangePercent").toDouble()
        val volume = ticker.optString("quoteVolume").toDoubleOrNull() ?: 0.0
        val rows = array("https://data-api.binance.vision/api/v3/klines?symbol=$symbol&interval=15m&limit=100")
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
        MarketSnapshot(MarketQuote(symbol, "CRYPTO", price, change, volume, source = "Binance 公共现货 API · 15m"), candles)
    }

    private fun yahoo(symbol: String): MarketSnapshot {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart".toHttpUrl().newBuilder()
            .addPathSegment(symbol).addQueryParameter("range", "5d").addQueryParameter("interval", "15m").build()
        val root = json(url.toString())
        val chart = root.optJSONObject("chart") ?: error("Yahoo Finance 响应缺少 chart")
        chart.optJSONObject("error")?.let { if (!it.isNull("description")) error(it.optString("description")) }
        val result = chart.optJSONArray("result")?.optJSONObject(0) ?: error("Yahoo Finance 未返回 $symbol")
        val meta = result.optJSONObject("meta") ?: JSONObject()
        val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: error("缺少 K 线")
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
        val last = candles.lastOrNull() ?: error("没有可用 K 线：$symbol")
        val price = meta.optDouble("regularMarketPrice", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: last.close
        val previous = meta.optDouble("chartPreviousClose", Double.NaN).takeIf { it.isFinite() && it > 0 }
            ?: meta.optDouble("previousClose", Double.NaN).takeIf { it.isFinite() && it > 0 }
            ?: candles.dropLast(1).lastOrNull()?.close ?: price
        val market = when {
            symbol.endsWith(".SS") || symbol.endsWith(".SZ") -> "A股"
            symbol.endsWith(".HK") || symbol == "^HSI" -> "港股/恒指"
            symbol.startsWith("^") -> "海外指数"
            else -> "美股/ETF"
        }
        MarketSnapshot(MarketQuote(symbol, market, price, if (previous > 0) (price-previous)/previous*100 else 0.0,
            last.volume, source = "Yahoo Finance Chart（非官方，可能延迟）"), candles)
    }

    private fun json(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", "MarketSentinel-Android/0.2").get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("行情接口 HTTP ${res.code}")
            return JSONObject(res.body?.string() ?: error("行情响应为空"))
        }
    }
    private fun array(url: String): JSONArray {
        val req = Request.Builder().url(url).header("User-Agent", "MarketSentinel-Android/0.2").get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("K 线接口 HTTP ${res.code}")
            return JSONArray(res.body?.string() ?: error("K 线响应为空"))
        }
    }
    private fun number(a: JSONArray, i: Int): Double? =
        if (i !in 0 until a.length() || a.isNull(i)) null else a.optString(i).toDoubleOrNull()?.takeIf { it.isFinite() }
}
