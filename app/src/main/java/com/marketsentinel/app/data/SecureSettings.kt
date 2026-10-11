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
}
