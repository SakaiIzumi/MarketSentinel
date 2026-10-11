package com.marketsentinel.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    companion object {
        const val DEFAULT_SYMBOLS = "BTCUSDT,ETHUSDT,SOLUSDT,^GSPC,^IXIC,^HSI,AAPL,MSFT,NVDA,600519.SS,399001.SZ,0700.HK"
    }
}
