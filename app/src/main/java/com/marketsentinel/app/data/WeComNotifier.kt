package com.marketsentinel.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeComNotifier {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun send(webhook: String, message: String): Result<Unit> =
        post(webhook, JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", withPushTime(message))))

    /**
     * 企业微信群机器人支持 Markdown 消息，不支持自定义 HTML 卡片。
     * 使用标题、颜色、重点字段和分隔线，形成更易读的告警卡片效果。
     */
    fun sendMarkdown(webhook: String, markdown: String): Result<Unit> =
        post(webhook, JSONObject().put("msgtype", "markdown").put("markdown", JSONObject().put("content", markdown + "\n\n> 推送时间：**" + pushTime() + "（手机本地时间）**")))

    private fun pushTime(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun withPushTime(message: String): String = message + "\n推送时间：" + pushTime() + "（手机本地时间）"

    private fun post(webhook: String, payload: JSONObject): Result<Unit> = runCatching {
        // 严格校验主机和路径，避免把告警发送到伪装域名。
        val parsed = webhook.toHttpUrlOrNull()
            ?: error("Webhook URL 格式无效")
        require(
            parsed.isHttps &&
                parsed.host == "qyapi.weixin.qq.com" &&
                parsed.encodedPath == "/cgi-bin/webhook/send" &&
                parsed.queryParameter("key").orEmpty().isNotBlank()
        ) {
            "仅允许企业微信官方机器人 Webhook 地址"
        }
        val request = Request.Builder()
            .url(parsed)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("企业微信 HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            if (json.optInt("errcode", -1) != 0) {
                error("企业微信发送失败，errcode=${json.optInt("errcode", -1)}")
            }
        }
    }
}
