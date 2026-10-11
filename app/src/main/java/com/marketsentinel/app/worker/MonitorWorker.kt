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
import com.marketsentinel.app.data.HoldingPosition
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

        val quantities = settings.getHoldingQuantities()
        val holdings = settings.getPurchasePrices().mapNotNull { (symbol, averageCost) ->
            val quantity = quantities[symbol] ?: return@mapNotNull null
            if (averageCost > 0 && quantity > 0) symbol to HoldingPosition(averageCost, quantity) else null
        }.toMap()
        val signals = fetched.flatMap { (_, result) ->
            result.getOrNull()?.let {
                StrategyEngine.evaluate(it, settings.getCryptoThreshold(), settings.getStockThreshold())
            }.orEmpty()
        }.filter { it.severity == "HIGH" || it.severity == "MEDIUM" }
            .sortedBy { if (it.kind.startsWith("ACTION_")) 0 else 1 }

        val prefs = applicationContext.getSharedPreferences("alert_dedupe", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var sent = 0
        for (signal in signals) {
            if (sent >= 5) break
            val key = "${signal.symbol}_${signal.kind}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val actionLabel = when (signal.kind) {
                "ACTION_BUY" -> "买入（分批建仓，不要一次性满仓）"
                "ACTION_SELL" -> "卖出/减仓已有持仓；空仓不买入、不做空"
                "ACTION_WAIT" -> "暂不买入、不卖出；不加仓"
                else -> null
            }
            val levels = if (signal.kind == "ACTION_BUY") buildString {
                append("\n止损参考线：${signal.stopLoss?.let { "%.8f".format(it).trimEnd('0').trimEnd('.') } ?: "暂无"}")
                append("\n第一目标参考价：${signal.target1?.let { "%.8f".format(it).trimEnd('0').trimEnd('.') } ?: "暂无"}")
                append("\n第二目标参考价：${signal.target2?.let { "%.8f".format(it).trimEnd('0').trimEnd('.') } ?: "暂无"}")
            } else ""
            val position = holdings[signal.symbol]
            val positionText = if (position != null && signal.price > 0) {
                val pnl = (signal.price - position.averageCost) / position.averageCost * 100.0
                "\n你的持仓：${position.quantity} 单位；买入均价：${position.averageCost}；持仓盈亏：${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)}%（未计手续费/税费）"
            } else ""
            val message = "【星澜 Pro/${signal.severity}】${signal.title}\n" +
                (actionLabel?.let { "操作结论：$it\n" } ?: "类型：行情观察，不是买卖指令\n") +
                "标的：${signal.symbol}\n现价：${signal.price}$levels$positionText\n规则：${signal.kind}\n" +
                "原因：${signal.reason}\n周期：15 分钟 K 线\n" +
                "规则信号不保证盈利；请核对行情时效，不会自动交易。"

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
                        if (actionLabel != null) {
                            appendLine("# <font color=\"${if (signal.kind == "ACTION_BUY") "info" else if (signal.kind == "ACTION_SELL") "warning" else "comment"}\">星澜 Pro · 交易操作结论</font>")
                            appendLine("> **操作结论：$actionLabel**")
                        } else {
                            appendLine("# <font color=\"warning\">星澜 Pro · ${if (signal.severity == "HIGH") "高风险提醒" else "行情观察提醒"}</font>")
                            appendLine("> **${signal.title}**")
                        }
                        appendLine()
                        appendLine("<font color=\"info\">标的</font>：**${signal.symbol}**")
                        appendLine("<font color=\"info\">当前价格</font>：**${signal.price}**")
                        if (position != null && signal.price > 0) {
                            val pnl = (signal.price - position.averageCost) / position.averageCost * 100.0
                            appendLine("<font color=\"info\">你的持仓</font>：**${position.quantity} 单位**")
                            appendLine("<font color=\"info\">买入均价</font>：**${position.averageCost}**")
                            appendLine("<font color=\"${if (pnl >= 0) "info" else "warning"}\">持仓盈亏</font>：**${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)}%**（未计手续费/税费）")
                        }
                        if (signal.kind == "ACTION_BUY") {
                            appendLine("<font color=\"warning\">止损参考线</font>：**${signal.stopLoss?.let { "%.8f".format(it).trimEnd('0').trimEnd('.') } ?: "暂无"}**")
                            appendLine("<font color=\"info\">第一目标参考价</font>：**${signal.target1?.let { "%.8f".format(it).trimEnd('0').trimEnd('.') } ?: "暂无"}**")
                            appendLine("<font color=\"info\">第二目标参考价</font>：**${signal.target2?.let { "%.8f".format(it).trimEnd('0').trimEnd('.') } ?: "暂无"}**")
                        }
                        appendLine("<font color=\"info\">信号类型</font>：${signal.kind}")
                        appendLine("<font color=\"info\">观察周期</font>：15 分钟 K 线")
                        appendLine()
                        appendLine("<font color=\"comment\">触发原因</font>")
                        appendLine("> ${signal.reason}")
                        appendLine()
                        appendLine("---")
                        appendLine("<font color=\"comment\">规则化技术信号，不保证盈利；仅供参考，不会自动交易。确认数据源与 K 线时效后再执行。</font>")
                    }
                    if (WeComNotifier().sendMarkdown(webhook, markdown).isSuccess) {
                        prefs.edit().putLong(webhookKey, now).apply()
                        sent++
                    }
                }
            }
        }
        // 按真实持仓成本计算盈亏；默认止损 -5%、止盈 +8%，只在进入新风险区间时提醒。
        val positionPrefs = applicationContext.getSharedPreferences("position_alert_states", Context.MODE_PRIVATE)
        // 本地通知与 Webhook 分别去重，避免本地通知成功但 Webhook 失败时丢失远端告警。
        val webhookPositionPrefs = applicationContext.getSharedPreferences("position_webhook_alert_states", Context.MODE_PRIVATE)
        for ((symbol, result) in fetched) {
            val position = holdings[symbol] ?: continue
            val snapshot = result.getOrNull() ?: continue
            val currentPrice = snapshot.quote.price
            if (!currentPrice.isFinite() || currentPrice <= 0 || position.averageCost <= 0 || position.quantity <= 0) continue
            val pnlPct = (currentPrice - position.averageCost) / position.averageCost * 100.0
            val pnlAmount = (currentPrice - position.averageCost) * position.quantity
            val zone = when {
                pnlPct <= -5.0 -> "STOP_LOSS"
                pnlPct >= 8.0 -> "TAKE_PROFIT"
                else -> "IN_RANGE"
            }
            val action = when (zone) {
                "STOP_LOSS" -> "卖出/减仓评估：已触发默认止损线（-5%）"
                "TAKE_PROFIT" -> "分批止盈：已达到默认止盈线（+8%）"
                else -> null
            }
            val stateKey = symbol.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val oldZone = positionPrefs.getString(stateKey, "IN_RANGE")
            if (zone == "IN_RANGE") {
                positionPrefs.edit().putString(stateKey, zone).apply()
                continue
            }
            if (oldZone == zone && (webhook.isBlank() || webhookPositionPrefs.getString(stateKey, "IN_RANGE") == zone)) continue
            val priceText = if (snapshot.quote.market == "CRYPTO") "${"%.8f".format(currentPrice)} USDT" else "%.4f".format(currentPrice)
            val costText = if (snapshot.quote.market == "CRYPTO") "${"%.8f".format(position.averageCost)} USDT" else "%.4f".format(position.averageCost)
            val currency = when (snapshot.quote.market) {
                "CRYPTO" -> "USDT"
                "A股" -> "CNY"
                "港股/恒指" -> "HKD"
                else -> "USD"
            }
            val message = "【星澜 Pro·持仓操作提醒】$symbol\n" +
                "操作结论：$action\n当前价格：$priceText\n买入均价：$costText\n" +
                "持有数量：${position.quantity}\n浮动盈亏：${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%（约 ${"%.2f".format(pnlAmount)} $currency，未计手续费/税费）\n" +
                "默认阈值：止损 -5%，止盈 +8%。这是起步规则，不保证盈利；核对行情后自行决定，不会自动下单。"
            val notificationId = ("position_${stateKey}_$zone").hashCode()
            val localSent = sendLocalNotification("持仓操作提醒：$symbol", message, notificationId)
            var webhookSent = false
            if (webhook.isNotBlank()) {
                val markdown = buildString {
                    appendLine("# <font color=\"${if (zone == "STOP_LOSS") "warning" else "info"}\">持仓操作提醒：$symbol</font>")
                    appendLine("> **操作结论：$action**")
                    appendLine()
                    appendLine("当前价格：**$priceText**")
                    appendLine("买入均价：**$costText**")
                    appendLine("持有数量：**${position.quantity}**")
                    appendLine("浮动盈亏：**${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%**（约 ${"%.2f".format(pnlAmount)} $currency，未计手续费/税费）")
                    appendLine()
                    appendLine("默认阈值：止损 -5%，止盈 +8%。")
                    appendLine("规则化风险提醒，不保证盈利；核对行情后自行决定，不会自动下单。")
                }
                webhookSent = WeComNotifier().sendMarkdown(webhook, markdown).isSuccess
                if (webhookSent) webhookPositionPrefs.edit().putString(stateKey, zone).apply()
            }
            if (localSent || webhookSent) positionPrefs.edit().putString(stateKey, zone).apply()
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
