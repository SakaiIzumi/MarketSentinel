package com.marketsentinel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.*
import com.marketsentinel.app.data.*
import com.marketsentinel.app.domain.StrategyEngine
import com.marketsentinel.app.worker.MonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("market-sentinel-monitor", ExistingPeriodicWorkPolicy.UPDATE, request)
        setContent { SentinelApp(SecureSettings(this)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SentinelApp(settings: SecureSettings) {
    var tab by remember { mutableIntStateOf(0) }
    var symbols by remember { mutableStateOf(settings.getSymbols()) }
    var webhook by remember { mutableStateOf(settings.getWebhook()) }
    var status by remember { mutableStateOf("首次使用请联网刷新行情。") }
    var busy by remember { mutableStateOf(false) }
    var quotes by remember { mutableStateOf(emptyList<MarketQuote>()) }
    var signals by remember { mutableStateOf(emptyList<StrategySignal>()) }
    var notice by remember { mutableStateOf("Webhook 尚未测试") }
    val scope = rememberCoroutineScope()
    val repo = remember { MarketRepository() }

    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF7DD3FC))) {
        Scaffold(topBar = { TopAppBar(title = { Text("行情哨兵 Market Sentinel") }) }, bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("行情") }, label = { Text("行情") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("策略") }, label = { Text("策略告警") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Text("设置") }, label = { Text("设置") })
            }
        }) { padding ->
            when (tab) {
                0 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("跨市场行情监控", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("默认数据源：Binance 公共现货 API（加密货币）及 Yahoo Finance Chart（股票/指数，非官方接口，可能延迟或限流）。无需数据源 API Key。") }
                    item { OutlinedTextField(symbols, { symbols = it.uppercase() }, label = { Text("监控标的（逗号分隔，最多 20 个）") }, supportingText = { Text("例：BTCUSDT,ETHUSDT,AAPL,600519.SS,0700.HK,^GSPC") }, modifier = Modifier.fillMaxWidth()) }
                    item { Button(enabled = !busy, onClick = {
                        busy = true; status = "正在获取行情…"
                        scope.launch {
                            try {
                                val list = symbols.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
                                settings.setSymbols(list.joinToString(","))
                                val results = withContext(Dispatchers.IO) {
                                    coroutineScope { list.map { s -> async { s to repo.snapshot(s) } }.awaitAll() }
                                }
                                val snaps = results.mapNotNull { it.second.getOrNull() }
                                quotes = snaps.map { it.quote }
                                signals = snaps.flatMap { StrategyEngine.evaluate(it) }
                                val failed = results.filter { it.second.isFailure }.map { it.first }
                                status = "成功 ${snaps.size}/${list.size} 个标的" + if (failed.isNotEmpty()) "；失败：${failed.take(4).joinToString()}" else ""
                                if (snaps.isEmpty()) status = "行情获取失败，请检查网络、代码或数据源限制。"
                            } catch (e: Exception) { status = "刷新失败：${e.message ?: "未知错误"}" }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "获取中…" else "刷新行情") } }
                    item { Text(status, style = MaterialTheme.typography.bodySmall) }
                    items(quotes) { q -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(q.symbol, style = MaterialTheme.typography.titleMedium)
                            Text("${if (q.changePct >= 0) "+" else ""}${"%.2f".format(q.changePct)}%", color = if (q.changePct >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                        }
                        Text(if (q.market == "CRYPTO") "${"%.8f".format(q.price)} USDT" else "%.2f".format(q.price), style = MaterialTheme.typography.headlineSmall)
                        Text("${q.market} · ${q.source}", style = MaterialTheme.typography.bodySmall)
                        Text("成交量：${"%.2f".format(q.volume)} · 更新：${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(q.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                    } } }
                }
                1 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("技术策略与风险观察", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("包含 EMA 趋势、RSI 超买超卖、MACD 动能转换、布林带、20 根 K 线突破/跌破、成交量放大、ATR 波动风险和涨跌幅阈值。信号仅用于观察，不自动交易、不保证盈利。") }
                    if (signals.isEmpty()) item { Text("暂无信号；请先刷新行情。") }
                    items(signals) { s -> ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${s.severity} · ${s.title}", style = MaterialTheme.typography.titleMedium)
                        Text("${s.symbol} · ${"%.4f".format(s.price)} · ${when(s.direction) { "LONG_WATCH" -> "偏多观察"; "SHORT_WATCH" -> "偏空观察"; else -> "风险观察" }}")
                        Text(s.reason)
                        if (s.stopLoss != null) Text("ATR 参考止损：${"%.4f".format(s.stopLoss)}；目标1：${"%.4f".format(s.target1)}；目标2：${"%.4f".format(s.target2)}")
                    } } }
                    item { OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth()) { Text("配置企业微信通知") } }
                }
                else -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("通知设置", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("Webhook 仅接受企业微信官方机器人地址，并加密保存在本机。不要把 Webhook 提交到仓库。") }
                    item { OutlinedTextField(webhook, { webhook = it }, label = { Text("企业微信群机器人 Webhook") }, modifier = Modifier.fillMaxWidth()) }
                    item { Button(onClick = { settings.setWebhook(webhook); notice = "已保存到本机加密存储" }, modifier = Modifier.fillMaxWidth()) { Text("安全保存") } }
                    item { Button(onClick = {
                        settings.setWebhook(webhook); notice = "正在发送测试消息…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { WeComNotifier().send(webhook, "【行情哨兵】Webhook 测试消息成功；此消息不代表交易信号。") }
                            notice = result.fold({ "发送成功，请检查企业微信群" }, { "发送失败：${it.message ?: "未知错误"}" })
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存并发送测试消息") } }
                    item { Text(notice, style = MaterialTheme.typography.bodySmall) }
                    item { Divider() }
                    item { Text("后台任务至少间隔 15 分钟，Android 可能延迟执行，不是实时行情服务。监控需配置 Webhook 后才会推送告警。") }
                }
            }
        }
    }
}
