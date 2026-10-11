package com.marketsentinel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.marketsentinel.app.data.MarketQuote
import com.marketsentinel.app.data.MarketRepository
import com.marketsentinel.app.data.SecureSettings
import com.marketsentinel.app.data.WeComNotifier
import com.marketsentinel.app.domain.StrategyEngine
import com.marketsentinel.app.worker.MonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        val work = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("market-sentinel-monitor", ExistingPeriodicWorkPolicy.UPDATE, work)
        setContent { MarketSentinelApp(settings) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketSentinelApp(settings: SecureSettings) {
    var tab by remember { mutableStateOf(0) }
    var quotes by remember { mutableStateOf<List<MarketQuote>>(emptyList()) }
    var status by remember { mutableStateOf("尚未获取行情。首次请求需要网络连接。") }
    var busy by remember { mutableStateOf(false) }
    var webhook by remember { mutableStateOf(settings.getWebhook()) }
    var webhookStatus by remember { mutableStateOf("Webhook 未测试") }
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var customSymbols by remember { mutableStateOf("BTCUSDT,ETHUSDT") }
    var signals by remember { mutableStateOf(emptyList<com.marketsentinel.app.data.StrategySignal>()) }
    val repo = remember { MarketRepository() }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF7DD3FC), secondary = androidx.compose.ui.graphics.Color(0xFF34D399))) {
        Scaffold(topBar = { TopAppBar(title = { Text("行情哨兵 Market Sentinel") }) }, bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("行情") }, label = { Text("行情") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("策略") }, label = { Text("策略告警") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("设置") }, label = { Text("设置") })
            }
        }) { padding ->
            when (tab) {
                0 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("跨市场行情监控", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("加密货币：当前提供 Binance 公共现货行情。股票行情需要在后续配置数据供应商，当前不显示伪造价格。", style = MaterialTheme.typography.bodyMedium) }
                    item {
                        OutlinedTextField(value = customSymbols, onValueChange = { customSymbols = it.uppercase() }, label = { Text("加密货币交易对（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        Button(enabled = !busy, onClick = {
                            busy = true; status = "正在获取行情…"
                            scope.launch {
                                val symbols = customSymbols.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
                                val result = withContext(Dispatchers.IO) { symbols.mapNotNull { repo.cryptoQuote(it).getOrNull() } }
                                quotes = result
                                signals = result.flatMap { StrategyEngine.evaluate(it, null) }
                                status = if (result.isEmpty()) "获取失败。请检查网络、交易对名称或数据源可用性。" else "更新完成：${result.size} 个标的 · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                                busy = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "获取中…" else "刷新行情") }
                    }
                    item { Text(status, style = MaterialTheme.typography.bodySmall) }
                    if (quotes.isEmpty()) item { ElevatedCard { Column(Modifier.padding(16.dp)) { Text("尚无行情数据"); Text("点击“刷新行情”获取 BTCUSDT、ETHUSDT 等公开报价。", style = MaterialTheme.typography.bodySmall) } } }
                    items(quotes) { quote ->
                        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(quote.symbol, style = MaterialTheme.typography.titleMedium); Text("${if (quote.changePct >= 0) "+" else ""}${"%.2f".format(quote.changePct)}%", color = if (quote.changePct >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error) }
                            Text("${"%.8f".format(quote.price)} USDT", style = MaterialTheme.typography.headlineSmall)
                            Text("24h 成交量：${"%.2f".format(quote.volume)} · ${quote.source}", style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
                1 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("策略与告警", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("当前为 MVP 规则：24 小时涨跌幅绝对值 ≥ 5%，以及刷新间的采样价格变化 ≥ 1.5%。不是完整的 EMA/RSI/MACD 多周期策略，不能单独用于交易决策。", style = MaterialTheme.typography.bodyMedium) }
                    if (signals.isEmpty()) item { Text("暂无策略信号。刷新行情后将根据可用数据计算告警。") }
                    items(signals) { s -> ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${s.severity} · ${s.title}", style = MaterialTheme.typography.titleMedium); Text("${s.symbol} · ${s.price}"); Text(s.reason); Text("${java.util.Date(s.createdAt)}", style = MaterialTheme.typography.bodySmall) } } }
                    item { OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth()) { Text("配置企业微信通知") } }
                }
                else -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("通知与数据设置", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("企业微信机器人 Webhook 将使用 Android 加密偏好设置保存。不要把 Webhook 粘贴到公开仓库、截图或日志。", style = MaterialTheme.typography.bodyMedium) }
                    item { OutlinedTextField(value = webhook, onValueChange = { webhook = it }, label = { Text("企业微信群机器人 Webhook") }, modifier = Modifier.fillMaxWidth(), singleLine = false) }
                    item { Button(onClick = { settings.setWebhook(webhook); webhookStatus = "已加密保存（请发送测试消息验证）" }, modifier = Modifier.fillMaxWidth()) { Text("安全保存 Webhook") } }
                    item { Button(onClick = {
                        settings.setWebhook(webhook)
                        webhookStatus = "正在发送测试消息…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { WeComNotifier().send(webhook, "【行情哨兵】企业微信 Webhook 测试成功。此消息不代表交易信号。") }
                            webhookStatus = result.fold({ "测试消息已发送，请检查企业微信群。" }, { "发送失败：${it.message ?: "未知错误"}" })
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存并发送测试消息") } }
                    item { Text(webhookStatus, style = MaterialTheme.typography.bodySmall) }
                    item { Divider() }
                    item { Text("后台监控说明", style = MaterialTheme.typography.titleMedium) }
                    item { Text("已安排 WorkManager 周期任务（至少约 15 分钟间隔，实际执行时间由 Android 决定）。它不是实时行情服务；系统省电、强制停止、厂商后台限制或网络问题都可能影响执行。", style = MaterialTheme.typography.bodyMedium) }
                    item { Text("股票行情接入", style = MaterialTheme.typography.titleMedium) }
                    item { Text("美股、A 股和港股需要选择支持对应交易所的行情供应商，并遵守其 API、延迟和授权限制。此 MVP 不会用随机数或模拟报价冒充真实行情。", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
