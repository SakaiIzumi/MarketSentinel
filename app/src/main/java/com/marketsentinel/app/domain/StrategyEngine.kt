package com.marketsentinel.app.domain

import com.marketsentinel.app.data.MarketQuote
import com.marketsentinel.app.data.StrategySignal
import kotlin.math.abs

/** Minimal, deterministic alert rules. Signals are informational and never place orders. */
object StrategyEngine {
    fun evaluate(quote: MarketQuote, previousPrice: Double?): List<StrategySignal> {
        val signals = mutableListOf<StrategySignal>()
        if (abs(quote.changePct) >= 5.0) {
            signals += StrategySignal(
                symbol = quote.symbol,
                kind = "VOLATILITY",
                title = if (quote.changePct > 0) "24小时涨幅异常" else "24小时跌幅异常",
                reason = "24小时涨跌幅 ${"%.2f".format(quote.changePct)}%，超过默认阈值 ±5%。这只是波动预警，不是买卖建议。",
                price = quote.price,
                severity = if (abs(quote.changePct) >= 10.0) "HIGH" else "MEDIUM"
            )
        }
        if (previousPrice != null && previousPrice > 0) {
            val move = (quote.price - previousPrice) / previousPrice * 100.0
            if (abs(move) >= 1.5) {
                signals += StrategySignal(
                    symbol = quote.symbol,
                    kind = "FAST_MOVE",
                    title = if (move > 0) "短时快速上涨" else "短时快速下跌",
                    reason = "相对上次采样价格变动 ${"%.2f".format(move)}%；采样间隔不固定，请结合 K 线和成交量确认。",
                    price = quote.price,
                    severity = "MEDIUM"
                )
            }
        }
        return signals
    }
}
