package com.marketsentinel.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marketsentinel.app.data.MarketRepository
import com.marketsentinel.app.data.SecureSettings
import com.marketsentinel.app.data.WeComNotifier
import com.marketsentinel.app.domain.StrategyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WorkManager periodic work is inexact (typically 15-minute minimum). This is not tick-level trading.
        val settings = SecureSettings(applicationContext)
        val webhook = settings.getWebhook()
        val quote = MarketRepository().cryptoQuote("BTCUSDT").getOrElse { return@withContext Result.retry() }
        val signals = StrategyEngine.evaluate(quote, null)
        if (webhook.isNotBlank()) {
            signals.forEach { signal ->
                val message = "【行情哨兵/${signal.severity}】${signal.title}\n标的：${signal.symbol}\n现价：${signal.price}\n原因：${signal.reason}\n仅为监控信号，不构成投资建议。"
                WeComNotifier().send(webhook, message)
            }
        }
        Result.success()
    }
}
