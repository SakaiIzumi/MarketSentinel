package com.marketsentinel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import kotlinx.coroutines.delay
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
    var newSymbol by remember { mutableStateOf("") }
    var refreshSeconds by remember { mutableIntStateOf(settings.getRefreshSeconds()) }
    var autoRefresh by remember { mutableStateOf(true) }
    var cryptoThresholdText by remember { mutableStateOf(settings.getCryptoThreshold().toString()) }
    var stockThresholdText by remember { mutableStateOf(settings.getStockThreshold().toString()) }
    val scope = rememberCoroutineScope()
    val repo = remember { MarketRepository() }

    suspend fun refreshQuotesNow() {
        if (busy) return
        busy = true
        status = "正在并发刷新行情…"
        try {
            val list = symbols.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct().take(20)
            if (list.isEmpty()) {
                status = "请先添加股票、指数或加密货币标的。"
                return
            }
            settings.setSymbols(list.joinToString(","))
            val results = withContext(Dispatchers.IO) {
                coroutineScope { list.map { symbol -> async { symbol to repo.snapshot(symbol) } }.awaitAll() }
            }
            val snapshots = results.mapNotNull { it.second.getOrNull() }
            val successful = snapshots.map { it.quote.symbol }.toSet()
            val merged = quotes.associateBy { it.symbol }.toMutableMap()
            snapshots.forEach { merged[it.quote.symbol] = it.quote }
            quotes = list.mapNotNull { merged[it] }
            val latestSignals = snapshots.flatMap {
                StrategyEngine.evaluate(it, settings.getCryptoThreshold(), settings.getStockThreshold())
            }
            signals = signals.filterNot { it.symbol in successful } + latestSignals
            val failed = results.filter { it.second.isFailure }
            status = "成功 " + snapshots.size + "/" + list.size + " 个标的 · " +
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) +
                if (failed.isNotEmpty()) " · 失败：" + failed.take(4).joinToString { pair ->
                    pair.first + "（" + (pair.second.exceptionOrNull()?.message ?: "接口异常") + "）"
                } else " · 全部正常"
            if (snapshots.isEmpty()) status = "本次均失败，保留上次成功行情。失败：" +
                failed.take(3).joinToString { it.first + "（" + (it.second.exceptionOrNull()?.message ?: "接口异常") + "）" }
        } catch (e: Exception) {
            status = "刷新失败：" + (e.message ?: "未知错误")
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refreshQuotesNow() }
    LaunchedEffect(refreshSeconds, autoRefresh, symbols) {
        if (autoRefresh) {
            while (true) {
                delay(refreshSeconds * 1000L)
                if (!busy) refreshQuotesNow()
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF7DD3FC))) {
        Scaffold(topBar = { TopAppBar(title = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.ShowChart, contentDescription = null); Column { Text("行情哨兵"); Text("MARKET SENTINEL", style = MaterialTheme.typography.labelSmall) } } }) }, bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.ShowChart, contentDescription = null) }, label = { Text("行情") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Tune, contentDescription = null) }, label = { Text("策略") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("设置") })
            }
        }) { padding ->
            when (tab) {
                0 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("跨市场行情监控", style = MaterialTheme.typography.headlineSmall) }
                    item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.Refresh, contentDescription = null); Text(if (autoRefresh) "自动刷新 · 每 " + refreshSeconds + " 秒" else "自动刷新已暂停", style = MaterialTheme.typography.titleMedium) }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { autoRefresh = !autoRefresh }) { Text(if (autoRefresh) "暂停自动刷新" else "开启自动刷新") }; Button(enabled = !busy, onClick = { scope.launch { refreshQuotesNow() } }) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(if (busy) "刷新中…" else "立即刷新") } } } } }
                    item { Text("默认数据源：Binance 公共现货 API（加密货币）及 Yahoo Finance Chart（股票/指数，非官方接口，可能延迟或限流）。无需数据源 API Key。") }
                    item { OutlinedTextField(symbols, { symbols = it.uppercase() }, label = { Text("监控标的（逗号分隔，最多 20 个）") }, supportingText = { Text("例：BTCUSDT,ETHUSDT,AAPL,600519.SS,0700.HK,^GSPC") }, modifier = Modifier.fillMaxWidth()) }
                    item {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("行情状态", style = MaterialTheme.typography.titleSmall)
                                Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                            }
                        }
                    }
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
                    item { Text("自选标的与刷新", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("支持 A 股、港股、美股、指数及加密货币。A 股格式：600519.SS（沪市）、000001.SZ（深市）；指数代码请使用 ^IXIC 等格式。最多 20 个标的。", style = MaterialTheme.typography.bodyMedium) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(newSymbol, { newSymbol = it.uppercase() }, label = { Text("添加标的代码") }, placeholder = { Text("AAPL / 600519.SS / SOLUSDT") }, modifier = Modifier.weight(1f), singleLine = true)
                        Button(onClick = {
                            val symbol = newSymbol.trim().uppercase()
                            if (symbol.matches(Regex("[A-Z0-9.^=_-]{1,24}")) && symbol !in symbols.split(",").map { it.trim() } && symbols.split(",").filter { it.isNotBlank() }.size < 20) {
                                symbols = (symbols.split(",").map { it.trim() }.filter { it.isNotBlank() } + symbol).distinct().joinToString(",")
                                settings.setSymbols(symbols)
                                newSymbol = ""
                                status = "已添加 " + symbol + "，正在等待行情刷新。"
                            } else status = "代码无效、已存在或自选数量已达 20 个。"
                        }) { Icon(Icons.Default.Add, contentDescription = null) }
                    } }
                    item { Text("快捷添加", style = MaterialTheme.typography.titleMedium) }
                    item {
                        val presets = listOf(
                            "贵州茅台" to "600519.SS",
                            "平安银行" to "000001.SZ",
                            "宁德时代" to "300750.SZ",
                            "比亚迪" to "002594.SZ",
                            "上证指数" to "000001.SS",
                            "深证成指" to "399001.SZ",
                            "腾讯控股" to "0700.HK",
                            "纳斯达克" to "^IXIC"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { preset ->
                                        OutlinedButton(
                                            modifier = Modifier.weight(1f),
                                            enabled = symbols.split(",").map { it.trim() }.size < 20 &&
                                                preset.second !in symbols.split(",").map { it.trim() },
                                            onClick = {
                                                symbols = (symbols.split(",").map { it.trim() }.filter { it.isNotBlank() } + preset.second)
                                                    .distinct().take(20).joinToString(",")
                                                settings.setSymbols(symbols)
                                                status = "已添加 ${preset.first}（${preset.second}），刷新后获取行情。"
                                            }
                                        ) { Text(preset.first + "\\n" + preset.second, style = MaterialTheme.typography.labelSmall) }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    item { Text("当前自选（点击 × 删除）", style = MaterialTheme.typography.titleMedium) }
                    item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        symbols.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { symbol ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(symbol, style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = {
                                    symbols = symbols.split(",").map { it.trim() }.filter { it.isNotBlank() && it != symbol }.joinToString(",")
                                    settings.setSymbols(symbols)
                                    quotes = quotes.filterNot { it.symbol == symbol }
                                    signals = signals.filterNot { it.symbol == symbol }
                                }) { Icon(Icons.Default.Close, contentDescription = "删除 " + symbol) }
                            }
                            HorizontalDivider()
                        }
                    } }
                    item { Text("刷新间隔（前台运行时）", style = MaterialTheme.typography.titleMedium) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 120, 300).forEach { seconds ->
                            if (refreshSeconds == seconds) Button(onClick = { refreshSeconds = seconds; settings.setRefreshSeconds(seconds) }) { Text(seconds.toString() + "秒") }
                            else OutlinedButton(onClick = { refreshSeconds = seconds; settings.setRefreshSeconds(seconds) }) { Text(seconds.toString() + "秒") }
                        }
                    } }
                    item { Text("涨跌幅告警阈值（百分比）", style = MaterialTheme.typography.titleMedium) }
                    item { OutlinedTextField(cryptoThresholdText, { cryptoThresholdText = it }, label = { Text("加密货币阈值 %") }, supportingText = { Text("范围 0.5%–30%，默认 5%") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { OutlinedTextField(stockThresholdText, { stockThresholdText = it }, label = { Text("股票/指数阈值 %") }, supportingText = { Text("范围 0.5%–20%，默认 3%") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item { Button(onClick = {
                        val crypto = cryptoThresholdText.toDoubleOrNull()
                        val stock = stockThresholdText.toDoubleOrNull()
                        if (crypto != null && crypto.isFinite() && crypto in 0.5..30.0 && stock != null && stock.isFinite() && stock in 0.5..20.0) {
                            settings.setCryptoThreshold(crypto)
                            settings.setStockThreshold(stock)
                            settings.setRefreshSeconds(refreshSeconds)
                            notice = "策略阈值和刷新设置已保存。前台刷新间隔 " + refreshSeconds + " 秒。"
                        } else notice = "阈值格式无效：加密货币 0.5–30，股票/指数 0.5–20。"
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存刷新与策略设置") } }
                    item { Divider() }
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
                    item { Text("前台刷新支持 30 秒起；切到后台后 Android 省电策略可能暂停或延迟刷新。系统后台告警仍由 WorkManager 执行，周期最短约 15 分钟。公开行情接口可能限流，标的越多越容易触发限制。") }
                }
            }
        }
    }
}
