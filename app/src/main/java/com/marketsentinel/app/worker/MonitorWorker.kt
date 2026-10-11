package com.marketsentinel.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marketsentinel.app.MainActivity
import com.marketsentinel.app.data.MarketRepository
import com.marketsentinel.app.data.SecureSettings
import com.marketsentinel.app.data.WeComNotifier
import com.marketsentinel.app.domain.StrategyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Background snapshot monitor. WorkManager runs periodically even after the Activity is closed.
 * Android may delay periodic work under Doze/battery restrictions; this is not a tick-by-tick feed.
 */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SecureSettings(applicationContext)
        val webhook = settings.getWebhook()
        val symbols = settings.getSymbols().split(",").map { it.trim().uppercase() }
            .filter { it.isNotBlank() }.distinct().take(20)
        if (symbols.isEmpty()) return@withContext Result.success()

        val repo = MarketRepository()
        val fetched = coroutineScope {
            symbols.map { symbol -> async { symbol to repo.snapshot(symbol) } }.awaitAll()
        }
        if (fetched.all { it.second.isFailure }) return@withContext Result.retry()

        val signals = fetched.flatMap { (_, result) ->
            result.getOrNull()?.let {
                StrategyEngine.evaluate(it, settings.getCryptoThreshold(), settings.getStockThreshold())
            }.orEmpty()
        }.filter { it.severity == "HIGH" || it.severity == "MEDIUM" }

        val prefs = applicationContext.getSharedPreferences("alert_dedupe", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var sent = 0
        for (signal in signals) {
            if (sent >= 5) break
            val key = "${signal.symbol}_${signal.kind}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val message = "【星澜 Pro/${signal.severity}】${signal.title}\n" +
                "标的：${signal.symbol}\n现价：${signal.price}\n规则：${signal.kind}\n" +
                "原因：${signal.reason}\n周期：15 分钟 K 线\n" +
                "仅为策略观察信号，不构成投资建议；不会自动交易。"

            // 手机本地通知独立于企业微信 Webhook；用户不配置 Webhook 也能收到提醒。
            val localKey = key + "_local"
            if (now - prefs.getLong(localKey, 0L) >= 4 * 60 * 60 * 1000L) {
                val notified = sendLocalNotification(signal.title, message, key.hashCode())
                if (notified) {
                    prefs.edit().putLong(localKey, now).apply()
                    sent++
                }
            }

            // 企业微信群机器人通知为可选项，与手机通知分别去重。
            if (webhook.isNotBlank()) {
                val webhookKey = key + "_wecom"
                if (now - prefs.getLong(webhookKey, 0L) >= 4 * 60 * 60 * 1000L) {
                    val markdown = buildString {
                        appendLine("# <font color=\"warning\">星澜 Pro · ${if (signal.severity == "HIGH") "高风险提醒" else "行情观察提醒"}</font>")
                        appendLine("> **${signal.title}**")
                        appendLine()
                        appendLine("<font color=\"info\">标的</font>：**${signal.symbol}**")
                        appendLine("<font color=\"info\">当前价格</font>：**${signal.price}**")
                        appendLine("<font color=\"info\">信号类型</font>：${signal.kind}")
                        appendLine("<font color=\"info\">观察周期</font>：15 分钟 K 线")
                        appendLine()
                        appendLine("<font color=\"comment\">触发原因</font>")
                        appendLine("> ${signal.reason}")
                        appendLine()
                        appendLine("---")
                        appendLine("<font color=\"comment\">策略观察信号，仅供参考，不构成投资建议；不会自动交易。</font>")
                    }
                    if (WeComNotifier().sendMarkdown(webhook, markdown).isSuccess) {
                        prefs.edit().putLong(webhookKey, now).apply()
                        sent++
                    }
                }
            }
        }
        // 买入价提醒：首次突破买入价时通知一次；回落到买入价或以下后重新布防。
        val purchasePrices = settings.getPurchasePrices()
        val crossingPrefs = applicationContext.getSharedPreferences("purchase_price_crossings", Context.MODE_PRIVATE)
        for ((symbol, result) in fetched) {
            val entryPrice = purchasePrices[symbol] ?: continue
            val snapshot = result.getOrNull() ?: continue
            val currentPrice = snapshot.quote.price
            if (!currentPrice.isFinite() || currentPrice <= 0) continue
            val stateKey = symbol.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val isAbove = currentPrice > entryPrice
            val localStateKey = "local_$stateKey"
            val wecomStateKey = "wecom_$stateKey"
            if (!isAbove) {
                crossingPrefs.edit().putBoolean(localStateKey, false).putBoolean(wecomStateKey, false).apply()
                continue
            }

            val pctAbove = (currentPrice - entryPrice) / entryPrice * 100.0
            val priceText = if (snapshot.quote.market == "CRYPTO") "${"%.8f".format(currentPrice)} USDT" else "%.4f".format(currentPrice)
            val entryText = if (snapshot.quote.market == "CRYPTO") "${"%.8f".format(entryPrice)} USDT" else "%.4f".format(entryPrice)
            val title = "价格高于你的买入价"
            val message = "【星澜 Pro·买入价提醒】$symbol 已高于你设置的买入价。\n" +
                "当前价格：$priceText\n你的买入价：$entryText\n" +
                "高于买入价：+${"%.2f".format(pctAbove)}%\n" +
                "这只是价格提醒，不代表建议买入或卖出。"
            if (!crossingPrefs.getBoolean(localStateKey, false)) {
                if (sendLocalNotification(title, message, ("buy_$stateKey").hashCode())) {
                    crossingPrefs.edit().putBoolean(localStateKey, true).apply()
                }
            }
            if (webhook.isNotBlank() && !crossingPrefs.getBoolean(wecomStateKey, false)) {
                val markdown = buildString {
                    appendLine("# 🚨 星澜 Pro · 买入价提醒")
                    appendLine("> **$symbol 已高于你的买入价**")
                    appendLine()
                    appendLine("**当前价格：** $priceText")
                    appendLine("**你的买入价：** $entryText")
                    appendLine("**高于买入价：** +${"%.2f".format(pctAbove)}%")
                    appendLine()
                    appendLine("---")
                    appendLine("> 价格观察提醒，不构成投资建议；不会自动交易。")
                }
                if (WeComNotifier().sendMarkdown(webhook, markdown).isSuccess) {
                    crossingPrefs.edit().putBoolean(wecomStateKey, true).apply()
                }
            }
        }

        Result.success()
    }

    private fun sendLocalNotification(title: String, message: String, notificationId: Int): Boolean {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "行情风险提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "股票与加密货币监控策略触发时发送提醒"
                }
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("星澜 Pro · $title")
            .setContentText(message.lineSequence().firstOrNull() ?: "检测到行情观察信号")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        private const val CHANNEL_ID = "market_sentinel_alerts"
    }
}
