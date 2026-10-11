package com.marketsentinel.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class SecureSettings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getWebhook(): String = prefs.getString("wecom_webhook", "") ?: ""
    fun setWebhook(value: String) { prefs.edit().putString("wecom_webhook", value.trim()).apply() }

    fun getSymbols(): String = prefs.getString("watch_symbols", DEFAULT_SYMBOLS) ?: DEFAULT_SYMBOLS
    fun setSymbols(value: String) {
        val normalized = value.split(",").map { it.trim().uppercase() }
            .filter { it.isNotBlank() }.distinct().take(20)
        prefs.edit().putString("watch_symbols", normalized.joinToString(",")).apply()
    }

    /** 获取用户为各个标的设置的买入参考价。 */
    fun getPurchasePrices(): Map<String, Double> {
        val raw = prefs.getString("purchase_prices", "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val symbol = keys.next()
                    val price = json.optDouble(symbol, Double.NaN)
                    if (symbol.isNotBlank() && price.isFinite() && price > 0) put(symbol, price)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setPurchasePrice(symbol: String, value: Double?) {
        val normalized = symbol.trim().uppercase()
        if (normalized.isBlank()) return
        val prices = getPurchasePrices().toMutableMap()
        if (value == null || !value.isFinite() || value <= 0) prices.remove(normalized)
        else prices[normalized] = value
        val json = JSONObject()
        prices.forEach { (key, price) -> json.put(key, price) }
        prefs.edit().putString("purchase_prices", json.toString()).apply()
    }

    fun getAssetNames(): Map<String, String> {
        val raw = prefs.getString("asset_names", "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val symbol = keys.next()
                    val name = json.optString(symbol).trim()
                    if (symbol.isNotBlank() && name.isNotBlank()) put(symbol, name)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setAssetName(symbol: String, name: String) {
        val normalized = symbol.trim().uppercase()
        if (normalized.isBlank() || name.isBlank()) return
        val names = getAssetNames().toMutableMap()
        names[normalized] = name.trim()
        val json = JSONObject()
        names.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString("asset_names", json.toString()).apply()
    }

    /** 用户持仓数量；买入均价沿用 purchase_prices，兼容旧设置。 */
    fun getHoldingQuantities(): Map<String, Double> {
        val raw = prefs.getString("holding_quantities", "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val symbol = keys.next()
                    val quantity = json.optDouble(symbol, Double.NaN)
                    if (symbol.isNotBlank() && quantity.isFinite() && quantity > 0) put(symbol, quantity)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setHoldingQuantity(symbol: String, value: Double?) {
        val normalized = symbol.trim().uppercase()
        if (normalized.isBlank()) return
        val quantities = getHoldingQuantities().toMutableMap()
        if (value == null || !value.isFinite() || value <= 0) quantities.remove(normalized)
        else quantities[normalized] = value
        val json = JSONObject()
        quantities.forEach { (key, quantity) -> json.put(key, quantity) }
        prefs.edit().putString("holding_quantities", json.toString()).apply()
    }

    fun getRefreshSeconds(): Int = prefs.getInt("refresh_seconds", 15).coerceIn(15, 300)
    fun setRefreshSeconds(value: Int) {
        prefs.edit().putInt("refresh_seconds", value.coerceIn(15, 300)).apply()
    }

    fun getCryptoThreshold(): Double = prefs.getString("crypto_threshold", "5.0")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.5..30.0 } ?: 5.0
    fun setCryptoThreshold(value: Double) {
        prefs.edit().putString("crypto_threshold", value.coerceIn(0.5, 30.0).toString()).apply()
    }

    fun getStockThreshold(): Double = prefs.getString("stock_threshold", "3.0")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.5..20.0 } ?: 3.0
    fun setStockThreshold(value: Double) {
        prefs.edit().putString("stock_threshold", value.coerceIn(0.5, 20.0).toString()).apply()
    }

    companion object {
        const val DEFAULT_SYMBOLS = "BTCUSDT,ETHUSDT,SOLUSDT,^GSPC,^IXIC,^HSI,AAPL,MSFT,NVDA,600519.SS,399001.SZ,0700.HK"
    }
}
