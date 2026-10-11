package com.marketsentinel.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marketsentinel.app.data.MarketRepository
import com.marketsentinel.app.data.SecureSettings
import com.marketsentinel.app.data.WeComNotifier
import com.marketsentinel.app.domain.StrategyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SecureSettings(applicationContext)
        val webhook = settings.getWebhook()
        if (webhook.isBlank()) return@withContext Result.success()
        val symbols = settings.getSymbols().split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
        if (symbols.isEmpty()) return@withContext Result.success()
        val repo = MarketRepository()
        val fetched = coroutineScope { symbols.map { s -> async { s to repo.snapshot(s) } }.awaitAll() }
        val signals = fetched.flatMap { (_, result) -> result.getOrNull()?.let { StrategyEngine.evaluate(it) }.orEmpty() }
            .filter { it.severity == "HIGH" || it.severity == "MEDIUM" }
        val prefs = applicationContext.getSharedPreferences("alert_dedupe", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var sent = 0
        for (signal in signals) {
            if (sent >= 5) break
            val key = "${signal.symbol}_${signal.kind}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
            if (now - prefs.getLong(key, 0L) < 4 * 60 * 60 * 1000L) continue
            val msg = "【行情哨兵/${signal.severity}】${signal.title}\n标的：${signal.symbol}\n现价：${signal.price}\n规则：${signal.kind}\n原因：${signal.reason}\n周期：15 分钟 K 线\n仅为策略观察信号，不构成投资建议；不会自动交易。"
            if (WeComNotifier().send(webhook, msg).isSuccess) {
                prefs.edit().putLong(key, now).apply()
                sent++
            }
        }
        if (fetched.all { it.second.isFailure }) Result.retry() else Result.success()
    }
}
