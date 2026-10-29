package com.dez.sports.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Refreshes the widget every 15 minutes, and roughly every 2 minutes while one of your games is live. */
object WidgetScheduler {
    private const val PERIODIC = "dez-widget-periodic"
    private const val CHAIN = "dez-widget-chain"
    private const val LIVE_INTERVAL_MIN = 2L

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
        refreshNow(context)
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHAIN, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(online).build(),
        )
    }

    /** Called from inside the worker, so it appends rather than replacing (which would cancel itself). */
    internal fun scheduleLiveFollowUp(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WidgetWorker>().setInitialDelay(LIVE_INTERVAL_MIN, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(CHAIN)
    }

    /** Refresh after the user leaves the app, so the widget matches what they just saw. */
    suspend fun refreshIfPlaced(context: Context) {
        if (GlanceAppWidgetManager(context).getGlanceIds(DezWidget::class.java).isNotEmpty()) refreshNow(context)
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val snap = WidgetData.refresh(applicationContext)
        DezWidget().updateAll(applicationContext)
        if (snap?.hasLive == true) WidgetScheduler.scheduleLiveFollowUp(applicationContext)
        return Result.success()
    }
}
