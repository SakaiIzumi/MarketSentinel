package com.marketsentinel.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.marketsentinel.app.worker.MonitorWorker
import java.util.concurrent.TimeUnit

/**
 * Restores scheduled checks after reboot/app update and resumes the explicitly enabled
 * foreground monitor when Android allows the app to start that service.
 */
class MonitorRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workManager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "market-sentinel-monitor",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        val immediate = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "market-sentinel-recovery-check",
            ExistingWorkPolicy.KEEP,
            immediate
        )

        val enabled = context.getSharedPreferences(LiveMonitorService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LiveMonitorService::class.java)
                )
            } catch (_: Exception) {
                // Android/OEM policy may block service startup; WorkManager checks remain scheduled.
            }
        }
    }
}
