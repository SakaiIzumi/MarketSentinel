package com.marketsentinel.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public Binance spot ticker. This adapter intentionally uses public market data only. */
class MarketRepository {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    fun cryptoQuote(symbol: String): Result<MarketQuote> = runCatching {
        require(symbol.matches(Regex("[A-Z0-9]{5,20}"))) { "交易对格式不正确" }
        val url = "https://api.binance.com/api/v3/ticker/24hr?symbol=$symbol"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("行情接口返回 HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: error("行情响应为空"))
            MarketQuote(
                symbol = symbol,
                market = "CRYPTO",
                price = json.getString("lastPrice").toDouble(),
                changePct = json.getString("priceChangePercent").toDouble(),
                volume = json.getString("volume").toDoubleOrNull() ?: 0.0,
                source = "Binance public REST"
            )
        }
    }

    /** Placeholder adapter contract for stock vendors; don't fabricate stock prices. */
    fun stockProviderStatus(): String = "股票行情需要配置合法的数据供应商 API；当前版本不会伪造报价。"
}
