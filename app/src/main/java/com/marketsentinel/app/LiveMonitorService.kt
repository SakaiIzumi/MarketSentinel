package com.marketsentinel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
                    if (cryptoSymbols.isNotEmpty() && !cryptoStreamConnected) {
                        cryptoSocket?.cancel()
                        cryptoSocket = repository.openCryptoTickerStream(
                            cryptoSymbols,
                            onQuote = { quote -> handleRealtimeQuote(quote) },
                            onStatus = { status ->
                                cryptoStreamConnected = status.contains("已连接")
                                updateNotification(if (cryptoStreamConnected) "加密货币实时推送已连接" else status)
                            }
                        )
                    } else if (cryptoSymbols.isEmpty()) {
                        cryptoSocket?.close(1000, "no crypto assets")
                        cryptoSocket = null
                        cryptoStreamConnected = false
                    }

                    // 使用现有监控逻辑检查技术策略、股票/指数及持仓；唯一任务防止重复堆积。
                    val now = System.currentTimeMillis()
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
            if (webhook.isBlank()) return@launch

            val averageCost = settings.getPurchasePrices()[quote.symbol] ?: return@launch
            val quantity = settings.getHoldingQuantities()[quote.symbol] ?: return@launch
            if (averageCost <= 0.0 || quantity <= 0.0 || quote.price <= 0.0) return@launch

            val pnlPct = (quote.price - averageCost) / averageCost * 100.0
            val zone = when {
                pnlPct <= -5.0 -> "STOP_LOSS"
                pnlPct >= 8.0 -> "TAKE_PROFIT"
                else -> "IN_RANGE"
            }
            val prefs = getSharedPreferences("position_webhook_alert_states", MODE_PRIVATE)
            val key = quote.symbol.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val oldZone = prefs.getString(key, "IN_RANGE")
            if (zone == "IN_RANGE") {
                if (oldZone != "IN_RANGE") prefs.edit().putString(key, zone).apply()
                return@launch
            }
            if (oldZone == zone) return@launch

            // WebSocket 每次价格跳动都会触发回调；失败时限制重试频率，避免刷屏。
            val now = System.currentTimeMillis()
            val attemptKey = "realtime_attempt_${key}_${zone}"
            if (now - prefs.getLong(attemptKey, 0L) < WEBHOOK_RETRY_GAP_MS) return@launch
            prefs.edit().putLong(attemptKey, now).apply()

            val stopLoss = zone == "STOP_LOSS"
            val action = if (stopLoss) "触发默认止损线（-5%）：评估卖出/减仓" else "触发默认止盈线（+8%）：评估分批止盈"
            val pnlAmount = (quote.price - averageCost) * quantity
            val markdown = buildString {
                appendLine("# <font color=\"${if (stopLoss) "warning" else "info"}\">星澜 Pro · 实时持仓提醒</font>")
                appendLine("> **${quote.symbol}：$action**")
                appendLine()
                appendLine("当前价格：**${"%.8f".format(quote.price)} USDT**")
                appendLine("买入均价：**${"%.8f".format(averageCost)} USDT**")
                appendLine("持有数量：**$quantity**")
                appendLine("浮动盈亏：**${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%**（约 ${"%.2f".format(pnlAmount)} USDT，未计手续费）")
                appendLine()
                appendLine("来源：Binance WebSocket 实时价格推送。默认阈值：止损 -5%、止盈 +8%。")
                appendLine("仅为风险提醒，不会自动下单；请核对价格和个人风险计划。")
            }
            if (WeComNotifier().sendMarkdown(webhook, markdown).isSuccess) {
                prefs.edit().putString(key, zone).apply()
                updateNotification("实时价格已连接 · 最近触发：${quote.symbol}")
            }
        }
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
        private const val WEBHOOK_RETRY_GAP_MS = 60_000L
    }
}
