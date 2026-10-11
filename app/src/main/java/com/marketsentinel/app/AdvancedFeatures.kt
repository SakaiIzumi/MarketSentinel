package com.marketsentinel.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.marketsentinel.app.data.MarketQuote
import kotlin.math.abs
import kotlin.math.floor

@Composable
internal fun MarketOverviewCard(quotes: List<MarketQuote>) {
    val positive = quotes.count { it.changePct > 0 }
    val negative = quotes.count { it.changePct < 0 }
    val crypto = quotes.count { it.market == "CRYPTO" }
    val average = quotes.map { it.changePct }.takeIf { it.isNotEmpty() }?.average()
    val best = quotes.maxByOrNull { it.changePct }
    val worst = quotes.minByOrNull { it.changePct }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF132033))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("市场全景", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2F6FF))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewMetric("已获取", "${quotes.size}", Modifier.weight(1f))
                OverviewMetric("上涨 / 下跌", "$positive / $negative", Modifier.weight(1f))
                OverviewMetric("加密资产", "$crypto", Modifier.weight(1f))
            }
            HorizontalDivider(color = Color(0xFF253349))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("自选平均涨跌", style = MaterialTheme.typography.labelSmall, color = Color(0xFF91A3BB))
                    Text(average?.let { "${if (it >= 0) "+" else ""}${"%.2f".format(it)}%" } ?: "--",
                        color = average?.let { if (it >= 0) Color(0xFF72E0C0) else Color(0xFFFF8290) } ?: Color.LightGray,
                        style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text("领涨", style = MaterialTheme.typography.labelSmall, color = Color(0xFF91A3BB))
                    Text(best?.symbol ?: "--", color = Color(0xFF72E0C0), style = MaterialTheme.typography.bodyMedium)
                    Text(best?.let { "${if (it.changePct >= 0) "+" else ""}${"%.2f".format(it.changePct)}%" } ?: "", style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f)) {
                    Text("领跌", style = MaterialTheme.typography.labelSmall, color = Color(0xFF91A3BB))
                    Text(worst?.symbol ?: "--", color = Color(0xFFFF8290), style = MaterialTheme.typography.bodyMedium)
                    Text(worst?.let { "${"%.2f".format(it.changePct)}%" } ?: "", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("统计仅基于当前已成功获取的自选报价，不代表整个市场。", style = MaterialTheme.typography.labelSmall, color = Color(0xFF91A3BB))
        }
    }
}

@Composable
private fun OverviewMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color(0xFF1C2B40)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF91A3BB))
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2F6FF))
        }
    }
}

@Composable
internal fun MarketSearchFilter(
    search: String,
    onSearch: (String) -> Unit,
    filter: String,
    onFilter: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索资产名称或代码") },
            placeholder = { Text("例如：比特币、BTCUSDT、英伟达") }
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "加密货币", "股票", "上涨", "下跌").forEach { option ->
                FilterChip(selected = filter == option, onClick = { onFilter(option) }, label = { Text(option) })
            }
        }
    }
}

/** Beginner-friendly position sizing helper; it never sends orders or stores portfolio balances. */
@Composable
internal fun PositionRiskCalculatorCard() {
    var capital by remember { mutableStateOf("10000") }
    var riskPercent by remember { mutableStateOf("1") }
    var entry by remember { mutableStateOf("100") }
    var stop by remember { mutableStateOf("95") }
    val capitalValue = capital.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val riskValue = riskPercent.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..5.0 }
    val entryValue = entry.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val stopValue = stop.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val valid = capitalValue != null && riskValue != null && entryValue != null && stopValue != null && entryValue != stopValue
    val riskMoney = if (valid) capitalValue!! * riskValue!! / 100.0 else null
    val units = if (valid) riskMoney!! / abs(entryValue!! - stopValue!!) else null
    val exposure = if (valid) units!! * entryValue!! else null
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF132033))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("仓位与风险计算器", style = MaterialTheme.typography.titleLarge, color = Color(0xFFF2F6FF))
            Text("先估算“如果止损触发，最多愿意亏多少”，再反推仓位。只做数学估算，不会下单。", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9C7DA))
            OutlinedTextField(capital, { capital = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("账户资金（示例金额）") })
            OutlinedTextField(riskPercent, { riskPercent = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("单笔风险比例 %（0.1–5）") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(entry, { entry = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("计划入场价") })
                OutlinedTextField(stop, { stop = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("止损参考价") })
            }
            if (valid) {
                Text("计划最大亏损：${"%.2f".format(riskMoney)}", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFC078))
                Text("理论数量上限：${"%.6f".format(units)} 单位", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF72E0C0))
                Text("对应名义仓位：${"%.2f".format(exposure)}（未计手续费、滑点、跳空与最小交易单位）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9C7DA))
            } else {
                Text("请输入有效正数，且入场价与止损价不能相同。风险比例限定 0.1%–5%。", color = Color(0xFFFF8290), style = MaterialTheme.typography.bodySmall)
            }
            Text("示例值仅用于演示。仓位计算不代表该交易值得执行，止损也不保证按指定价格成交。", style = MaterialTheme.typography.labelSmall, color = Color(0xFF91A3BB))
        }
    }
}
