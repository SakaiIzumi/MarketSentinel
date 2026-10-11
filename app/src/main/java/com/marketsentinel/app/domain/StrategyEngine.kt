package com.marketsentinel.app.domain

import com.marketsentinel.app.data.Candle
import com.marketsentinel.app.data.MarketSnapshot
import com.marketsentinel.app.data.StrategySignal
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Technical indicator watch rules. Alerts are informational; this engine never places orders. */
object StrategyEngine {
    fun evaluate(snapshot: MarketSnapshot): List<StrategySignal> {
        val q = snapshot.quote
        val bars = snapshot.candles.filter { it.close > 0 && it.close.isFinite() }
        val close = bars.map { it.close }
        if (close.size < 26) return if (abs(q.changePct) >= if (q.market == "CRYPTO") 5 else 3)
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
            add("TREND_UP", "短线趋势偏强", "15 分钟 EMA9 高于 EMA21，价格位于 EMA21 上方。等待回踩和成交量确认。", direction = "LONG_WATCH")
        else if (e9 < e21 && close.last() < e21 && (e50 == null || close.last() < e50))
            add("TREND_DOWN", "短线趋势偏弱", "15 分钟 EMA9 低于 EMA21，价格位于 EMA21 下方。", direction = "SHORT_WATCH")
        if (rsiValue != null && rsiValue >= 70) add("RSI_HIGH", "RSI 超买风险", "RSI(14)=${"%.1f".format(rsiValue)}；强趋势中超买可能持续。", "HIGH")
        if (rsiValue != null && rsiValue <= 30) add("RSI_LOW", "RSI 超卖观察", "RSI(14)=${"%.1f".format(rsiValue)}；需等待止跌确认。", "HIGH")
        if (macd.size >= 2 && macd[macd.lastIndex-1] <= 0 && macd.last() > 0)
            add("MACD_BULL", "MACD 动能转强", "MACD 柱由负转正，短线动能可能改善。", direction = "LONG_WATCH")
        if (macd.size >= 2 && macd[macd.lastIndex-1] >= 0 && macd.last() < 0)
            add("MACD_BEAR", "MACD 动能转弱", "MACD 柱由正转负，留意支撑和风险。", direction = "SHORT_WATCH")
        if (bb != null && close.last() > bb.second) add("BB_UP", "布林上轨突破观察", "收盘价高于布林上轨，可能趋势扩张或假突破。")
        if (bb != null && close.last() < bb.first) add("BB_DOWN", "布林下轨跌破观察", "收盘价低于布林下轨，等待结构确认。")
        val vr = volRatio
        if (vr != null && vr >= 1.5 && close.last() > prev.maxOf { it.high })
            add("BREAKOUT_VOLUME", "放量突破前高", "价格突破前 20 根 K 线高点，成交量约为均量 ${"%.1f".format(vr)} 倍。", "HIGH", "LONG_WATCH")
        if (vr != null && vr >= 1.5 && close.last() < prev.minOf { it.low })
            add("BREAKDOWN_VOLUME", "放量跌破前低", "价格跌破前 20 根 K 线低点，成交量约为均量 ${"%.1f".format(vr)} 倍。", "HIGH", "SHORT_WATCH")
        if (vr != null && vr >= 2) add("VOLUME_SPIKE", "成交量异常放大", "最新 K 线成交量约为前序均量 ${"%.1f".format(vr)} 倍。")
        val atrPct = atrValue?.div(q.price)?.times(100)
        if (atrPct != null && atrPct >= if (q.market == "CRYPTO") 2 else 1)
            add("ATR_HIGH", "波动率升高", "ATR/价格=${"%.2f".format(atrPct)}%，短线波动风险偏高。", "HIGH")
        val threshold = if (q.market == "CRYPTO") 5.0 else 3.0
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
