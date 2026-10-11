package com.marketsentinel.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

data class Candle(val timestamp: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
data class MarketQuote(
    val symbol: String, val market: String, val price: Double, val changePct: Double,
    val volume: Double = 0.0, val updatedAt: Long = System.currentTimeMillis(), val source: String = ""
)
data class MarketSnapshot(val quote: MarketQuote, val candles: List<Candle>)
data class StrategySignal(
    val symbol: String, val kind: String, val title: String, val reason: String, val price: Double,
    val createdAt: Long = System.currentTimeMillis(), val severity: String = "INFO",
    val direction: String = "WATCH", val stopLoss: Double? = null, val target1: Double? = null, val target2: Double? = null
)
@Entity(tableName = "watchlist")
data class WatchItem(@PrimaryKey val symbol: String, val market: String, val enabled: Boolean = true)
@Entity(tableName = "alert_history")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val symbol: String, val title: String,
    val detail: String, val price: Double, val createdAt: Long = System.currentTimeMillis()
)
