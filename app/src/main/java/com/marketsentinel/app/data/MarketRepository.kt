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
        val entered = rawSymbol.trim().uppercase()\n        val symbol = if (entered == "AIXIC") "^IXIC" else entered
        require(symbol.matches(Regex("[A-Z0-9.^=_-]{1,24}"))) { "标的代码格式不正确：" + rawSymbol }
        if (isCryptoSymbol(symbol)) crypto(symbol)
        else if (isChinaSymbol(symbol)) chinaQuote(symbol)
        else globalQuote(symbol)
    }

    /**
     * Yahoo Finance 被限流/403 时，回退到 Stooq CSV 日线。
     * Stooq 日线通常不是实时行情；UI 会明确显示来源，不能与实时报价混为一谈。
     */
    private fun globalQuote(symbol: String): MarketSnapshot {
        var yahooError: Throwable? = null
        try {
            return yahoo(symbol)
        } catch (e: Exception) {
            yahooError = e
        }
        try {
            return stooqQuote(symbol)
        } catch (e: Exception) {
            throw IllegalStateException(
                "主行情源 Yahoo Finance 失败（${yahooError?.message ?: "未知错误"}）；备用 Stooq 也失败（${e.message ?: "未知错误"}）"
            )
        }
    }

    private fun stooqSymbol(symbol: String): String? = when (symbol) {
        "^GSPC" -> "^spx"
        "^IXIC" -> "^ndq"
        "^HSI" -> "^hsi"
        else -> when {
            symbol.matches(Regex("^[A-Z]{1,6}$")) -> symbol.lowercase() + ".us"
            symbol.matches(Regex("^[0-9]{4,5}\\.HK$")) -> symbol.removeSuffix(".HK").lowercase() + ".hk"
            else -> null
        }
    }

    private fun stooqQuote(symbol: String): MarketSnapshot {
        val mapped = stooqSymbol(symbol) ?: error("此代码暂无 Stooq 备用映射")
        val url = "https://stooq.com/q/d/l/?s=" + mapped + "&i=d"
        val request = Request.Builder().url(url)
            .header("User-Agent", "MarketSentinel-Android/0.4")
            .header("Accept", "text/csv,*/*")
            .get().build()
        val csv = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Stooq HTTP " + response.code)
            response.body?.string() ?: error("Stooq 返回空数据")
        }
        val rows = csv.lineSequence().drop(1).mapNotNull { line ->
            val f = line.split(",")
            if (f.size < 6) return@mapNotNull null
            val date = f[0].trim()
            val open = f[1].toDoubleOrNull() ?: return@mapNotNull null
            val high = f[2].toDoubleOrNull() ?: return@mapNotNull null
            val low = f[3].toDoubleOrNull() ?: return@mapNotNull null
            val close = f[4].toDoubleOrNull() ?: return@mapNotNull null
            val volume = f[5].toDoubleOrNull() ?: 0.0
            val time = try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(date)?.time ?: return@mapNotNull null
            } catch (_: Exception) { return@mapNotNull null }
            Candle(time, open, high, low, close, volume)
        }.toList().takeLast(100)
        require(rows.size >= 2) { "Stooq 没有足够日线数据（可能不支持此代码或当前网络无法访问）" }
        val last = rows.last()
        val previous = rows[rows.lastIndex - 1].close
        val market = when {
            symbol.endsWith(".HK") || symbol == "^HSI" -> "港股/恒指"
            symbol.startsWith("^") -> "海外指数"
            else -> "美股/ETF"
        }
        return MarketSnapshot(
            MarketQuote(symbol, market, last.close,
                if (previous > 0) (last.close - previous) / previous * 100 else 0.0,
                last.volume, source = "Stooq · 日线（可能延迟）"),
            rows
        )
    }

    private fun isChinaSymbol(symbol: String): Boolean =
        symbol.matches(Regex("^[0-9]{6}\\.(SS|SZ)$"))

    /**
     * A 股快照：使用新浪公开行情接口。该接口提供最新报价，不承诺历史 K 线；
     * 因此 A 股当前以价格/涨跌幅监控为主，历史技术指标不足时不伪造信号。
     */
    private fun chinaQuote(symbol: String): MarketSnapshot {
        val code = symbol.substringBefore(".")
        val exchange = symbol.substringAfter(".")
        val sinaCode = (if (exchange == "SS") "sh" else "sz") + code
        val url = "https://hq.sinajs.cn/list=" + sinaCode
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .header("Referer", "https://finance.sina.com.cn/")
            .header("Accept", "*/*")
            .get().build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("新浪 A 股行情 HTTP " + response.code)
            val bytes = response.body?.bytes() ?: error("新浪 A 股行情响应为空")
            String(bytes, java.nio.charset.Charset.forName("GBK"))
        }
        val raw = body.substringAfter('=', "").trim().trimEnd(';').trim('"')
        val fields = raw.split(",")
        require(fields.size >= 10 && fields[0].isNotBlank()) {
            "未获取到 A 股行情，请检查代码或交易时间"
        }
        val previous = fields[2].toDoubleOrNull() ?: error("A 股昨收价无效")
        val price = fields[3].toDoubleOrNull() ?: error("A 股最新价无效")
        val volume = fields[8].toDoubleOrNull() ?: 0.0
        require(price > 0 && price.isFinite()) { "A 股暂时无有效报价（可能停牌或休市）" }
        val change = if (previous > 0) (price - previous) / previous * 100 else 0.0
        val candle = Candle(System.currentTimeMillis(), price, price, price, price, volume)
        return MarketSnapshot(
            MarketQuote(symbol, "A股", price, change, volume, source = "新浪公开行情 · 实时报价"),
            listOf(candle)
        )
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
