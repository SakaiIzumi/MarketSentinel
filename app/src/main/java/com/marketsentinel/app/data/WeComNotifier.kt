package com.marketsentinel.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WeComNotifier {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    fun send(webhook: String, message: String): Result<Unit> = runCatching {
        // 严格校验主机和路径，不能只用 contains()，避免把告警发往伪装域名。
        val parsed = webhook.toHttpUrlOrNull()
            ?: error("Webhook URL 格式无效")
        require(parsed.isHttps && parsed.host == "qyapi.weixin.qq.com" &&
            parsed.encodedPath == "/cgi-bin/webhook/send" && parsed.queryParameter("key").orEmpty().isNotBlank()) {
            "仅允许企业微信官方机器人 Webhook 地址"
        }
        val payload = JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", message)).toString()
        val request = Request.Builder().url(parsed).post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("企业微信 HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            if (json.optInt("errcode", -1) != 0) error("企业微信发送失败，errcode=${json.optInt("errcode", -1)}")
        }
    }
}
