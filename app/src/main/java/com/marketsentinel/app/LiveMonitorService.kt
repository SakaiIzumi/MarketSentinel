package com.marketsentinel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.marketsentinel.app.data.MarketQuote
import com.marketsentinel.app.data.MarketRepository
import com.marketsentinel.app.data.SecureSettings
import com.marketsentinel.app.data.WeComNotifier
import com.marketsentinel.app.worker.MonitorWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 用户主动开启的常驻行情监控。
 * 加密资产通过 Binance WebSocket 推送价格；其他策略/股票快照每分钟尝试检查一次。
 * Android 仍可能因厂商省电策略、断网或系统强制停止而中断，不能承诺绝对实时。
 */
class LiveMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var cryptoStreamConnected = false
    @Volatile private var cryptoStreamConnecting = false
    @Volatile private var lastConnectionAttemptAt = 0L
    private var activeCryptoSymbols = ""
    @Volatile private var cryptoSocket: okhttp3.WebSocket? = null
    private var lastFastCheckAt = 0L
    private val repository by lazy { MarketRepository() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动后台行情监控…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            stopMonitoring()
            stopSelf()
            return START_NOT_STICKY
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch {
                while (isActive) {
                    val settings = SecureSettings(applicationContext)
                    val symbols = settings.getSymbols().split(",")
                        .map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct().take(20)
                    val cryptoSymbols = symbols.filter { it.endsWith("USDT") }
                    val symbolKey = cryptoSymbols.joinToString(",")
                    val now = System.currentTimeMillis()

                    // 监控列表变化时重建订阅，避免后台一直订阅旧的币种列表。
                    if (symbolKey != activeCryptoSymbols) {
                        cryptoSocket?.cancel()
                        cryptoSocket = null
                        cryptoStreamConnected = false
                        cryptoStreamConnecting = false
                        activeCryptoSymbols = symbolKey
                    }
                    if (cryptoSymbols.isEmpty()) {
                        cryptoSocket?.close(1000, "no crypto assets")
                        cryptoSocket = null
                        cryptoStreamConnected = false
                        cryptoStreamConnecting = false
                    } else {
                        // 连接握手卡住时清理旧连接；正常连接/连接中都不重复创建 WebSocket。
                        if (cryptoStreamConnecting && now - lastConnectionAttemptAt > SOCKET_CONNECT_TIMEOUT_MS) {
                            cryptoSocket?.cancel()
                            cryptoSocket = null
                            cryptoStreamConnecting = false
                            cryptoStreamConnected = false
                        }
                        val reconnectReady = lastConnectionAttemptAt == 0L ||
                            now - lastConnectionAttemptAt >= RECONNECT_CHECK_INTERVAL_MS
                        if (!cryptoStreamConnected && !cryptoStreamConnecting && reconnectReady) {
                            lastConnectionAttemptAt = now
                            cryptoStreamConnecting = true
                            cryptoSocket = repository.openCryptoTickerStream(
                                cryptoSymbols,
                                onQuote = { quote -> handleRealtimeQuote(quote) },
                                onStatus = { status ->
                                    when {
                                        status.contains("已连接") -> {
                                            cryptoStreamConnected = true
                                            cryptoStreamConnecting = false
                                        }
                                        status.contains("失败") || status.contains("断开") -> {
                                            cryptoStreamConnected = false
                                            cryptoStreamConnecting = false
                                        }
                                    }
                                    updateNotification(if (cryptoStreamConnected) "加密货币实时推送已连接" else status)
                                }
                            )
                            if (cryptoSocket == null) cryptoStreamConnecting = false
                        }
                    }

                    // 使用现有监控逻辑检查技术策略、股票/指数及持仓；唯一任务防止重复堆积。
                    if (now - lastFastCheckAt >= FAST_CHECK_INTERVAL_MS) {
                        lastFastCheckAt = now
                        val request = OneTimeWorkRequestBuilder<MonitorWorker>()
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                            )
                            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                            .build()
                        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                            FAST_CHECK_WORK,
                            ExistingWorkPolicy.KEEP,
                            request
                        )
                    }
                    delay(RECONNECT_CHECK_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun handleRealtimeQuote(quote: MarketQuote) {
        serviceScope.launch {
            val settings = SecureSettings(applicationContext)
            val webhook = settings.getWebhook()
            val averageCost = settings.getPurchasePrices()[quote.symbol] ?: return@launch
            val quantity = settings.getHoldingQuantities()[quote.symbol] ?: return@launch
            if (averageCost <= 0.0 || quantity <= 0.0 || quote.price <= 0.0) return@launch

            val pnlPct = (quote.price - averageCost) / averageCost * 100.0
            val zone = when {
                pnlPct <= -5.0 -> "STOP_LOSS"
                pnlPct >= 8.0 -> "TAKE_PROFIT"
                else -> "IN_RANGE"
            }
            val key = quote.symbol.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val webhookPrefs = getSharedPreferences("position_webhook_alert_states", MODE_PRIVATE)
            val localPrefs = getSharedPreferences("position_local_alert_states", MODE_PRIVATE)

            if (zone == "IN_RANGE") {
                webhookPrefs.edit().putString(key, zone).apply()
                localPrefs.edit().putString(key, zone).apply()
                return@launch
            }

            val stopLoss = zone == "STOP_LOSS"
            val action = if (stopLoss) "触发默认止损线（-5%）：评估卖出/减仓" else "触发默认止盈线（+8%）：评估分批止盈"
            val pnlAmount = (quote.price - averageCost) * quantity
            val priceText = "%.8f".format(quote.price) + " USDT"
            val costText = "%.8f".format(averageCost) + " USDT"
            val message = "【星澜 Pro · 实时持仓提醒】${quote.symbol}\n" +
                "操作结论：$action\n当前价格：$priceText\n买入均价：$costText\n" +
                "持有数量：$quantity\n浮动盈亏：${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%（约 ${"%.2f".format(pnlAmount)} USDT，未计手续费）\n" +
                "默认阈值：止损 -5%，止盈 +8%。风险提醒，不会自动下单。"

            // 本地通知和企业微信各自记录状态；Webhook 未配置/失败不能阻断手机提醒。
            val now = System.currentTimeMillis()
            if (localPrefs.getString(key, "IN_RANGE") != zone) {
                val localAttemptKey = "attempt_${key}_${zone}"
                if (now - localPrefs.getLong(localAttemptKey, 0L) >= WEBHOOK_RETRY_GAP_MS) {
                    localPrefs.edit().putLong(localAttemptKey, now).apply()
                    if (sendRealtimeLocalNotification(quote.symbol, action, message, "${key}_${zone}".hashCode())) {
                        localPrefs.edit().putString(key, zone).apply()
                    }
                }
            }

            if (webhook.isNotBlank() && webhookPrefs.getString(key, "IN_RANGE") != zone) {
                val attemptKey = "realtime_attempt_${key}_${zone}"
                if (now - webhookPrefs.getLong(attemptKey, 0L) >= WEBHOOK_RETRY_GAP_MS) {
                    webhookPrefs.edit().putLong(attemptKey, now).apply()
                    val markdown = buildString {
                        appendLine("# <font color=\"${if (stopLoss) "warning" else "info"}\">星澜 Pro · 实时持仓提醒</font>")
                        appendLine("> **${quote.symbol}：$action**")
                        appendLine()
                        appendLine("当前价格：**$priceText**")
                        appendLine("买入均价：**$costText**")
                        appendLine("持有数量：**$quantity**")
                        appendLine("浮动盈亏：**${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%**（约 ${"%.2f".format(pnlAmount)} USDT，未计手续费）")
                        appendLine()
                        appendLine("来源：Binance WebSocket 实时价格推送。默认阈值：止损 -5%、止盈 +8%。")
                        appendLine("仅为风险提醒，不会自动下单；请核对价格和个人风险计划。")
                    }
                    if (WeComNotifier().sendMarkdown(webhook, markdown).isSuccess) {
                        webhookPrefs.edit().putString(key, zone).apply()
                        updateNotification("实时价格已连接 · 最近触发：${quote.symbol}")
                    }
                }
            }
        }
    }

    private fun sendRealtimeLocalNotification(symbol: String, title: String, message: String, notificationId: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false

        val channelId = "market_sentinel_realtime_alerts"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "实时持仓风险提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "价格实时触发止盈止损时发送手机通知"
                }
            )
            if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("星澜 Pro · $symbol · $title")
            .setContentText(message.lineSequence().firstOrNull() ?: "实时持仓风险提醒")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        return runCatching {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, LiveMonitorService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("星澜 Pro · 后台监控运行中")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n加密资产使用实时 WebSocket；其他策略每分钟尝试检查。"))
            .setContentIntent(openPending)
            .addAction(0, "停止监控", stopPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台实时监控", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "MarketSentinel 后台行情监控状态与实时风险提醒"
                }
            )
        }
    }

    private fun stopMonitoring() {
        loopJob?.cancel()
        loopJob = null
        cryptoSocket?.close(1000, "user stopped monitoring")
        cryptoSocket = null
        cryptoStreamConnected = false
        cryptoStreamConnecting = false
        activeCryptoSymbols = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.marketsentinel.app.action.STOP_LIVE_MONITOR"
        const val PREFS = "live_monitor"
        const val FAST_CHECK_WORK = "market-sentinel-fast-check"
        private const val CHANNEL_ID = "market_sentinel_live_monitor"
        private const val NOTIFICATION_ID = 4207
        private const val FAST_CHECK_INTERVAL_MS = 60_000L
        private const val RECONNECT_CHECK_INTERVAL_MS = 15_000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 30_000L
        private const val WEBHOOK_RETRY_GAP_MS = 60_000L
    }
}
