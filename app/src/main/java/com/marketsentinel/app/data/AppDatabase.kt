package com.marketsentinel.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketDao {
    @Query("SELECT * FROM watchlist ORDER BY symbol") fun watchlist(): Flow<List<WatchItem>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertWatch(item: WatchItem)
    @Query("DELETE FROM watchlist WHERE symbol = :symbol") suspend fun deleteWatch(symbol: String)
    @Insert suspend fun insertAlert(alert: AlertEntity)
    @Query("SELECT * FROM alert_history ORDER BY createdAt DESC LIMIT 200") fun alerts(): Flow<List<AlertEntity>>
}

@Database(entities = [WatchItem::class, AlertEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun marketDao(): MarketDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "market_sentinel.db").build().also { INSTANCE = it }
        }
    }
}
