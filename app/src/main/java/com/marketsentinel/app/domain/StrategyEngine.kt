package com.marketsentinel.app.domain

import com.marketsentinel.app.data.Candle
import com.marketsentinel.app.data.MarketSnapshot
import com.marketsentinel.app.data.StrategySignal
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Technical indicator watch rules. Alerts are informational; this engine never places orders. */
object StrategyEngine {
    fun evaluate(snapshot: MarketSnapshot, cryptoThreshold: Double = 5.0, stockThreshold: Double = 3.0): List<StrategySignal> {
        val q = snapshot.quote
        val threshold = if (q.market == "CRYPTO") cryptoThreshold else stockThreshold
        val bars = snapshot.candles.filter { it.close > 0 && it.close.isFinite() }
        val close = bars.map { it.close }
        if (close.size < 26) return if (abs(q.changePct) >= threshold)
            listOf(StrategySignal(q.symbol, "VOLATILITY", "涨跌幅异常观察",
                "涨跌幅 ${"%.2f".format(q.changePct)}%；历史 K 线不足，暂不输出技术指标。", q.price, severity = "HIGH"))
        else emptyList()

        val e9 = ema(close, 9).last()
        val e21 = ema(close, 21).last()
        val e50 = if (close.size >= 50) ema(close, 50).last() else null
        val rsiValue = rsi(close, 14)
        val macd = macd(close)
        val atrValue = atr(bars, 14)
        val bb = bollinger(close, 20)
        val avgVol = bars.takeLast(21).dropLast(1).map { it.volume }.average().takeIf { it > 0 }
        val volRatio = avgVol?.let { bars.last().volume / it }
        val prev = bars.dropLast(1).takeLast(20)
        val out = mutableListOf<StrategySignal>()
        fun add(kind: String, title: String, reason: String, severity: String = "MEDIUM", direction: String = "WATCH") {
            val risk = atrValue
            val sign = if (direction == "LONG_WATCH") 1.0 else -1.0
            out += StrategySignal(q.symbol, kind, title, reason, q.price, severity = severity, direction = direction,
                stopLoss = if (direction == "WATCH" || risk == null) null else q.price - sign * 1.5 * risk,
                target1 = if (direction == "WATCH" || risk == null) null else q.price + sign * 1.5 * risk,
                target2 = if (direction == "WATCH" || risk == null) null else q.price + sign * 3.0 * risk)
        }
        if (e9 > e21 && close.last() > e21 && (e50 == null || close.last() > e50))
            add("TREND_UP", "短线趋势偏强", "近期价格走势偏强，并且高于近期平均水平。含义：值得继续观察，不是马上买入；等价格回落后站稳，并确认成交量配合。", direction = "LONG_WATCH")
        else if (e9 < e21 && close.last() < e21 && (e50 == null || close.last() < e50))
            add("TREND_DOWN", "短线趋势偏弱", "近期价格走势偏弱，并且低于近期平均水平。含义：暂时谨慎，不要急着抄底；如果已经持有，检查预先制定的退出计划。", direction = "SHORT_WATCH")
        if (rsiValue != null && rsiValue >= 70) add("RSI_HIGH", "RSI 超买风险", "RSI(14)=${"%.1f".format(rsiValue)}；近期上涨速度偏快。不代表一定会下跌，但不宜只因上涨就追买，等待价格稳定。", "HIGH")
        if (rsiValue != null && rsiValue <= 30) add("RSI_LOW", "RSI 超卖观察", "RSI(14)=${"%.1f".format(rsiValue)}；近期下跌速度偏快。不代表已经见底，先等价格止跌走稳，不要急着抄底。", "HIGH")
        if (macd.size >= 2 && macd[macd.lastIndex-1] <= 0 && macd.last() > 0)
            add("MACD_BULL", "MACD 动能转强", "上涨/下跌动能指标刚转向偏强。它只是辅助信号，最好同时看到价格站稳和成交量配合。", direction = "LONG_WATCH")
        if (macd.size >= 2 && macd[macd.lastIndex-1] >= 0 && macd.last() < 0)
            add("MACD_BEAR", "MACD 动能转弱", "上涨/下跌动能指标刚转向偏弱。不要只凭这一条卖出，结合持仓和风险计划判断。", direction = "SHORT_WATCH")
        if (bb != null && close.last() > bb.second) add("BB_UP", "布林上轨突破观察", "价格超出近期常见波动范围的上沿。可能继续走强，也可能冲高回落；避免看到上涨就追买。")
        if (bb != null && close.last() < bb.first) add("BB_DOWN", "布林下轨跌破观察", "价格跌出近期常见波动范围的下沿。可能继续走弱，也可能反弹；不要把跌得多当作买入理由。")
        val vr = volRatio
        if (vr != null && vr >= 1.5 && close.last() > prev.maxOf { it.high })
            add("BREAKOUT_VOLUME", "放量突破前高", "价格突破前 20 根 K 线高点，成交量约为均量 ${"%.1f".format(vr)} 倍。", "HIGH", "LONG_WATCH")
        if (vr != null && vr >= 1.5 && close.last() < prev.minOf { it.low })
            add("BREAKDOWN_VOLUME", "放量跌破前低", "价格跌破前 20 根 K 线低点，成交量约为均量 ${"%.1f".format(vr)} 倍。", "HIGH", "SHORT_WATCH")
        if (vr != null && vr >= 2) add("VOLUME_SPIKE", "成交量异常放大", "最近一根 K 线的成交量约为平时的 ${"%.1f".format(vr)} 倍。")
        val atrPct = atrValue?.div(q.price)?.times(100)
        if (atrPct != null && atrPct >= if (q.market == "CRYPTO") 2 else 1)
            add("ATR_HIGH", "波动率升高", "近期正常波动幅度约占价格的${"%.2f".format(atrPct)}%，短线波动风险偏高。", "HIGH")
        if (abs(q.changePct) >= threshold) add("VOLATILITY", "涨跌幅异常观察",
            "数据源报告涨跌幅 ${"%.2f".format(q.changePct)}%，超过观察阈值 ${threshold}%。", "HIGH")
        return out.distinctBy { it.kind }.take(8)
    }
    private fun ema(v: List<Double>, p: Int): List<Double> {
        val a = 2.0 / (p + 1); val out = ArrayList<Double>(); var x = v.first(); out += x
        for (i in 1 until v.size) { x = v[i] * a + x * (1-a); out += x }; return out
    }
    private fun rsi(v: List<Double>, p: Int): Double? {
        if (v.size <= p) return null
        val d = (v.size-p until v.size).map { v[it]-v[it-1] }
        val g = d.filter { it > 0 }.sum()/p; val l = -d.filter { it < 0 }.sum()/p
        if (l == 0.0) return if (g == 0.0) 50.0 else 100.0
        return 100 - 100/(1 + g/l)
    }
    private fun macd(v: List<Double>): List<Double> {
        val f=ema(v,12); val s=ema(v,26); val line=v.indices.map { f[it]-s[it] }; val sig=ema(line,9)
        return line.indices.map { line[it]-sig[it] }
    }
    private fun bollinger(v: List<Double>, p: Int): Pair<Double,Double>? {
        if (v.size < p) return null
        val x=v.takeLast(p); val m=x.average(); val sd=sqrt(x.sumOf { (it-m).pow(2) }/p)
        return (m-2*sd) to (m+2*sd)
    }
    private fun atr(c: List<Candle>, p: Int): Double? {
        if (c.size < p+1) return null
        val x=c.takeLast(p+1)
        return x.drop(1).mapIndexed { i, b -> maxOf(b.high-b.low, abs(b.high-x[i].close), abs(b.low-x[i].close)) }.average()
    }
}
