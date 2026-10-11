package com.marketsentinel.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
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


private fun localizedAssetName(symbol: String): String {
    val s = symbol.uppercase()
    return when (s) {
        "BTCUSDT", "BTCUSD", "BTC" -> "比特币"
        "ETHUSDT", "ETHUSD", "ETH" -> "以太坊"
        "SOLUSDT", "SOLUSD", "SOL" -> "Solana"
        "BNBUSDT", "BNBUSD", "BNB" -> "币安币"
        "XRPUSDT", "XRPUSD", "XRP" -> "瑞波币"
        "DOGEUSDT", "DOGEUSD", "DOGE" -> "狗狗币"
        "ADAUSDT", "ADAUSD", "ADA" -> "艾达币"
        "AVAXUSDT", "AVAXUSD", "AVAX" -> "雪崩协议"
        "LINKUSDT", "LINKUSD", "LINK" -> "Chainlink"
        "DOTUSDT", "DOTUSD", "DOT" -> "波卡"
        "AAPL" -> "苹果公司"
        "MSFT" -> "微软"
        "NVDA" -> "英伟达"
        "GOOGL", "GOOG" -> "谷歌"
        "AMZN" -> "亚马逊"
        "META" -> "Meta"
        "TSLA" -> "特斯拉"
        "NFLX" -> "奈飞"
        "^GSPC" -> "标普 500 指数"
        "^IXIC", "AIXIC" -> "纳斯达克综合指数"
        "^DJI" -> "道琼斯工业指数"
        "^HSI" -> "恒生指数"
        "600519.SS" -> "贵州茅台"
        "000001.SS" -> "上证综合指数"
        "000001.SZ" -> "平安银行"
        "399001.SZ" -> "深证成指"
        "300750.SZ" -> "宁德时代"
        "002594.SZ" -> "比亚迪"
        "0700.HK" -> "腾讯控股"
        "9988.HK" -> "阿里巴巴"
        "3690.HK" -> "美团"
        else -> when {
            s.endsWith(".HK") -> "港股"
            s.endsWith(".SS") || s.endsWith(".SZ") -> "A 股"
            s.startsWith("^") -> "市场指数"
            s.endsWith("USDT") -> "加密货币"
            else -> "股票 / ETF"
        }
    }
}

private data class WatchlistAsset(val name: String, val symbol: String, val category: String)

private val watchlistAssetCatalog = listOf(
    WatchlistAsset("比特币", "BTCUSDT", "加密货币"),
    WatchlistAsset("以太坊", "ETHUSDT", "加密货币"),
    WatchlistAsset("索拉纳", "SOLUSDT", "加密货币"),
    WatchlistAsset("币安币", "BNBUSDT", "加密货币"),
    WatchlistAsset("瑞波币", "XRPUSDT", "加密货币"),
    WatchlistAsset("狗狗币", "DOGEUSDT", "加密货币"),
    WatchlistAsset("艾达币", "ADAUSDT", "加密货币"),
    WatchlistAsset("雪崩协议", "AVAXUSDT", "加密货币"),
    WatchlistAsset("Chainlink", "LINKUSDT", "加密货币"),
    WatchlistAsset("波卡", "DOTUSDT", "加密货币"),
    WatchlistAsset("莱特币", "LTCUSDT", "加密货币"),
    WatchlistAsset("柴犬币", "SHIBUSDT", "加密货币"),
    WatchlistAsset("苹果", "AAPL", "美股"),
    WatchlistAsset("微软", "MSFT", "美股"),
    WatchlistAsset("英伟达", "NVDA", "美股"),
    WatchlistAsset("特斯拉", "TSLA", "美股"),
    WatchlistAsset("亚马逊", "AMZN", "美股"),
    WatchlistAsset("谷歌", "GOOGL", "美股"),
    WatchlistAsset("Meta", "META", "美股"),
    WatchlistAsset("标普500", "^GSPC", "指数"),
    WatchlistAsset("纳斯达克", "^IXIC", "指数"),
    WatchlistAsset("道琼斯", "^DJI", "指数"),
    WatchlistAsset("恒生指数", "^HSI", "指数"),
    WatchlistAsset("贵州茅台", "600519.SS", "A股"),
    WatchlistAsset("平安银行", "000001.SZ", "A股"),
    WatchlistAsset("宁德时代", "300750.SZ", "A股"),
    WatchlistAsset("比亚迪", "002594.SZ", "A股"),
    WatchlistAsset("上证指数", "000001.SS", "指数"),
    WatchlistAsset("深证成指", "399001.SZ", "指数"),
    WatchlistAsset("腾讯控股", "0700.HK", "港股"),
    WatchlistAsset("阿里巴巴", "9988.HK", "港股"),
    WatchlistAsset("美团", "3690.HK", "港股")
)

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("market-sentinel-monitor", ExistingPeriodicWorkPolicy.UPDATE, request)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { SentinelApp(SecureSettings(this)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SentinelApp(settings: SecureSettings) {
    var tab by remember { mutableIntStateOf(0) }
    var symbols by remember { mutableStateOf(settings.getSymbols()) }
    var webhook by remember { mutableStateOf(settings.getWebhook()) }
    var purchasePriceTexts = remember { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { settings.getPurchasePrices().forEach { (symbol, price) -> put(symbol, price.toString()) } } }
    var status by remember { mutableStateOf("首次使用请联网刷新行情。") }
    var streamStatus by remember { mutableStateOf("等待加密货币实时连接…") }
    var busy by remember { mutableStateOf(false) }
    var quotes by remember { mutableStateOf(emptyList<MarketQuote>()) }
    var signals by remember { mutableStateOf(emptyList<StrategySignal>()) }
    var notice by remember { mutableStateOf("Webhook 尚未测试") }
    var newSymbol by remember { mutableStateOf("") }
    var quoteSearch by remember { mutableStateOf("") }
    var marketFilter by remember { mutableStateOf("全部") }
    val filteredQuotes = quotes.filter { q ->
        val matchesSearch = quoteSearch.isBlank() || q.symbol.contains(quoteSearch.trim(), true) || localizedAssetName(q.symbol).contains(quoteSearch.trim(), true)
        val matchesMarket = when (marketFilter) {
            "加密货币" -> q.market == "CRYPTO"
            "股票" -> q.market != "CRYPTO"
            "上涨" -> q.changePct > 0
            "下跌" -> q.changePct < 0
            else -> true
        }
        matchesSearch && matchesMarket
    }
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
            val list = symbols.split(",").map { it.trim().uppercase() }.map { if (it == "AIXIC") "^IXIC" else it }.filter { it.isNotBlank() }.distinct().take(20)
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

    DisposableEffect(symbols, repo) {
        val cryptoSymbols = symbols.split(",").map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it.endsWith("USDT") }
        val socket = repo.openCryptoTickerStream(
            cryptoSymbols,
            onQuote = { quote ->
                quotes = (quotes.filterNot { it.symbol == quote.symbol } + quote)
                    .sortedBy { cryptoSymbols.indexOf(it.symbol).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            },
            onStatus = { message -> streamStatus = message }
        )
        onDispose { socket?.close(1000, "watchlist changed") }
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

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF72E0C0),
        secondary = Color(0xFF83B8FF),
        tertiary = Color(0xFF4ADE80),
        background = Color(0xFF080D18),
        surface = Color(0xFF111827),
        surfaceVariant = Color(0xFF1C2738),
        error = Color(0xFFFF6B7A)
    )) {
        Scaffold(topBar = { TopAppBar(title = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.ShowChart, contentDescription = null); Column { Text("星澜 Pro"); Text("DIGITAL ASSET TERMINAL", style = MaterialTheme.typography.labelSmall) } } }) }, bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.ShowChart, contentDescription = null) }, label = { Text("行情") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Tune, contentDescription = null) }, label = { Text("策略") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("设置") })
            }
        }) { padding ->
            when (tab) {
                0 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("一眼看懂市场", style = MaterialTheme.typography.titleLarge, color = Color(0xFFF2F6FF))
                            Text("股票 · 指数 · 加密资产", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9AAAC0))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = { autoRefresh = !autoRefresh }, label = { Text(if (autoRefresh) "● 自动监控中" else "自动监控已暂停") })
                                AssistChip(onClick = { scope.launch { refreshQuotesNow() } }, enabled = !busy, label = { Text(if (busy) "更新中…" else "立即更新") }, leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) })
                            }
                        }
                    }
                    item { MarketOverviewCard(quotes) }
                    item { MarketSearchFilter(search = quoteSearch, onSearch = { quoteSearch = it }, filter = marketFilter, onFilter = { marketFilter = it }) }

                    if (quotes.isEmpty()) {
                        item {
                            ElevatedCard(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF111827))
                            ) {
                                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.ShowChart, contentDescription = null, tint = Color(0xFF72E0C0), modifier = Modifier.size(34.dp))
                                    Text(if (busy) "正在连接行情…" else "还没有行情数据", style = MaterialTheme.typography.titleLarge)
                                    Text("点击“立即更新”检查网络或标的代码。获取失败时不会用虚构价格填充。", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9AAAC0))
                                }
                            }
                        }
                    }
                    items(filteredQuotes) { q ->
                        val positive = q.changePct >= 0
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF111B2B))
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(localizedAssetName(q.symbol), style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2F6FF), maxLines = 1)
                                        Text("${q.symbol} · ${if (q.market == "CRYPTO") "加密资产" else "股票/指数"}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF92A5BF), maxLines = 1)
                                    }
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            if (q.market == "CRYPTO") "${"%.8f".format(q.price)} USDT" else "%.2f".format(q.price),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color(0xFFF5F8FF),
                                            maxLines = 1
                                        )
                                        Surface(shape = RoundedCornerShape(8.dp), color = if (positive) Color(0xFF123C35) else Color(0xFF42212B)) {
                                            Text(
                                                "${if (positive) "+" else ""}${"%.2f".format(q.changePct)}%",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                color = if (positive) Color(0xFF72E0C0) else Color(0xFFFF8290),
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(q.source, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8497B1), maxLines = 1)
                                    Text(
                                        "更新 " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(q.updatedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF8497B1),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF132033))
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF72E0C0), modifier = Modifier.size(16.dp))
                                    Text("连接与刷新状态", style = MaterialTheme.typography.titleSmall, color = Color(0xFFF2F6FF))
                                    Spacer(Modifier.weight(1f))
                                    Text(if (busy) "更新中" else if (autoRefresh) "自动更新" else "已暂停", style = MaterialTheme.typography.labelSmall, color = Color(0xFF72E0C0))
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9C7DA), maxLines = 3)
                                Text("币价实时通道：$streamStatus", style = MaterialTheme.typography.labelSmall, color = if (streamStatus.contains("已连接")) Color(0xFF72E0C0) else Color(0xFFFFC078), maxLines = 2)
                                Text("前台币价使用 WebSocket；股票行情可能延迟。后台定时检查约每 15 分钟一次。", style = MaterialTheme.typography.labelSmall, color = Color(0xFF91A3BB))
                            }
                        }
                    }
                }
                1 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("技术策略与风险观察", style = MaterialTheme.typography.headlineSmall) }
                    item { PositionRiskCalculatorCard() }
                    item { Text("新手指南：偏强=走势变好，但不是立即买入；偏弱=走势变差，不要急着抄底；涨跌异常=短期波动较大，先核实报价和消息；放量突破=价格超过近期高点且交易量增加，但也可能是假突破。建议先看趋势，再等价格站稳和成交量确认。买入前先决定最多能承受多少亏损，并提前设置退出规则；单笔计划风险可考虑控制在账户资金的 0.5%–1%，这不是收益保证。不确定时可以不交易。以下信号仅供参考，不自动交易，也不能保证盈利。") }
                    if (signals.isEmpty()) item { Text("暂无信号；请先刷新行情。") }
                    items(signals) { s -> ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${if (s.severity == "HIGH") "重点提醒" else "观察提醒"} · ${s.title}", style = MaterialTheme.typography.titleMedium)
                        Text("${s.symbol} · ${"%.4f".format(s.price)} · ${when(s.direction) { "LONG_WATCH" -> "偏多观察"; "SHORT_WATCH" -> "偏空观察"; else -> "风险观察" }}")
                        Text(s.reason)
                        if (s.stopLoss != null) Text("风险参考价位（并非保证成交价）：止损观察 ${"%.4f".format(s.stopLoss)}；目标1：${"%.4f".format(s.target1)}；目标2：${"%.4f".format(s.target2)}")
                    } } }
                    item { OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth()) { Text("配置企业微信通知") } }
                }
                else -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("自选标的与刷新", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("支持 A 股、港股、美股、指数及加密货币。A 股格式：600519.SS（沪市）、000001.SZ（深市）；指数代码请使用 ^IXIC 等格式。最多 20 个标的。名称会显示中文别名，代码仍保留用于准确识别。", style = MaterialTheme.typography.bodyMedium) }
                    item {
                        OutlinedTextField(
                            value = newSymbol,
                            onValueChange = { newSymbol = it.take(40) },
                            label = { Text("搜索资产名称或代码") },
                            placeholder = { Text("试试：比特币、英伟达、腾讯、BTC、ETH") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.ShowChart, contentDescription = null) },
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                    item {
                        val query = newSymbol.trim()
                        val matches = watchlistAssetCatalog.filter { asset ->
                            query.isBlank() ||
                                asset.name.contains(query, ignoreCase = true) ||
                                asset.symbol.contains(query, ignoreCase = true) ||
                                asset.category.contains(query, ignoreCase = true)
                        }.take(if (query.isBlank()) 8 else 12)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (query.isBlank()) "热门资产 · 点击添加" else "搜索结果（名称和代码均可匹配）", style = MaterialTheme.typography.titleSmall, color = Color(0xFFB9C7DA))
                            matches.forEach { asset ->
                                val alreadyAdded = asset.symbol in symbols.split(",").map { it.trim() }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF111B2B),
                                    onClick = {
                                        if (alreadyAdded) {
                                            status = "${asset.name} 已在自选中。"
                                        } else if (symbols.split(",").filter { it.isNotBlank() }.size >= 20) {
                                            status = "自选数量已达 20 个上限。"
                                        } else {
                                            symbols = (symbols.split(",").map { it.trim() }.filter { it.isNotBlank() } + asset.symbol).distinct().joinToString(",")
                                            settings.setSymbols(symbols)
                                            status = "已添加 ${asset.name}（${asset.symbol}），正在等待行情刷新。"
                                            newSymbol = ""
                                        }
                                    }
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF1C2D40)) {
                                            Text(asset.symbol.takeWhile { it.isLetterOrDigit() }.take(3), modifier = Modifier.padding(8.dp), color = Color(0xFF72E0C0), style = MaterialTheme.typography.labelMedium)
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(asset.name, color = Color(0xFFF2F6FF), style = MaterialTheme.typography.bodyLarge)
                                            Text("${asset.symbol} · ${asset.category}", color = Color(0xFF92A5BF), style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text(if (alreadyAdded) "已添加" else "+ 添加", color = if (alreadyAdded) Color(0xFF8497B1) else Color(0xFF72E0C0), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            if (query.isNotBlank() && matches.isEmpty() && query.matches(Regex("[A-Za-z0-9.^=_-]{1,24}"))) {
                                OutlinedButton(
                                    onClick = {
                                        val symbol = query.uppercase()
                                        if (symbol !in symbols.split(",").map { it.trim() } && symbols.split(",").filter { it.isNotBlank() }.size < 20) {
                                            symbols = (symbols.split(",").map { it.trim() }.filter { it.isNotBlank() } + symbol).distinct().joinToString(",")
                                            settings.setSymbols(symbols)
                                            status = "已添加代码 ${symbol}；请确认数据源支持该标的。"
                                            newSymbol = ""
                                        } else status = "代码已存在或自选数量已达 20 个。"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("按代码添加：${query.uppercase()}") }
                            }
                        }
                    }
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
                                        ) { Text(preset.first + "\n" + preset.second, style = MaterialTheme.typography.labelSmall) }
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
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(localizedAssetName(symbol), style = MaterialTheme.typography.bodyLarge); Text(symbol, style = MaterialTheme.typography.labelSmall, color = Color(0xFF92A5BF)) }
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
                    item { Divider() }
                    item { Text("买入价提醒", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("给每个自选标的填写你的买入价格。行情高于买入价时，App 会发送手机通知，并在已配置 Webhook 时发送企业微信提醒；首次高于时提醒一次，跌回买入价以下后再次突破才会重新提醒。留空表示关闭该标的的买入价提醒。", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB9C7DA)) }
                    items(symbols.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }) { symbol ->
                        OutlinedTextField(
                            value = purchasePriceTexts[symbol] ?: "",
                            onValueChange = { purchasePriceTexts[symbol] = it },
                            label = { Text(localizedAssetName(symbol) + " · " + symbol + " 买入价") },
                            placeholder = { Text(if (symbol.endsWith("USDT")) "例如 65000.00 USDT" else "例如 180.50") },
                            supportingText = { Text("输入大于 0 的价格；加密货币按 USDT 计价") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    item { Button(onClick = {
                        val entries = symbols.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
                        val invalidSymbol = entries.firstOrNull { symbol ->
                            val raw = purchasePriceTexts[symbol]?.trim().orEmpty()
                            raw.isNotBlank() && (raw.toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true)
                        }
                        if (invalidSymbol != null) {
                            notice = "买入价格式无效（$invalidSymbol）：请填写大于 0 的数字，或留空关闭提醒。"
                        } else {
                            entries.forEach { symbol ->
                                val raw = purchasePriceTexts[symbol]?.trim().orEmpty()
                                settings.setPurchasePrice(symbol, if (raw.isBlank()) null else raw.toDoubleOrNull())
                            }
                            notice = "买入价设置已保存；后台会在下一次定时检查时生效。"
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存买入价提醒") } }
                    item { Text("行情刷新与后台监控", style = MaterialTheme.typography.titleMedium) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60, 120, 300).forEach { seconds ->
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
                            val result = withContext(Dispatchers.IO) { WeComNotifier().send(webhook, "【星澜行情】Webhook 测试消息成功；此消息不代表交易信号。") }
                            notice = result.fold({ "发送成功，请检查企业微信群" }, { "发送失败：${it.message ?: "未知错误"}" })
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存并发送测试消息") } }
                    item { Text(notice, style = MaterialTheme.typography.bodySmall) }
                    item { Divider() }
                    item { Text("无需保持 App 打开：后台监控由 Android WorkManager 定期执行，约每 15 分钟检查一次，并可发送手机通知及企业微信告警。Android 可能因省电、网络限制而延迟；强制停止 App 后，通常需重新打开才能恢复调度。后台使用定时行情快照，不是持续 WebSocket 推送；前台虚拟币才使用实时推送。公开行情接口可能限流，标的越多越容易触发限制。") }
                }
            }
        }
    }
}
