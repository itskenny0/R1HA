package com.github.itskenny0.r1ha.core.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Background refresh job. When scheduled (opt-in via the Advanced setting), Android wakes
 * the app every [PERIOD_MS] minutes (subject to Doze/standby relaxations) and fires
 * [HaRepository.listAllEntities] so the entity cache stays warm even after the WS link
 * has been suspended by the OS during sleep.
 *
 * Uses the platform [JobScheduler] directly rather than adding a WorkManager dependency:
 * the contract is the same (constrained periodic work), the dep cost is ~600 KB of lib +
 * its dexed indirections, and we don't need WorkManager's chaining / observability features.
 *
 * NetworkType.ANY: HA installs are mostly LAN-only, where 'unmetered' wouldn't always be
 * reported on every Wi-Fi config; the user opted into "refresh" knowing the network cost.
 */
class BackgroundRefreshJob : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var inFlight: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? App ?: run {
            // No graph — can't refresh. Tell scheduler not to retry (the env is broken
            // in a way it can't fix).
            return false
        }
        inFlight = scope.launch {
            runCatching {
                val outcome = app.graph.haRepository.listAllEntities()
                outcome.fold(
                    onSuccess = { entities ->
                        R1Log.i(
                            "BackgroundRefreshJob",
                            "warm cache: ${entities.size} entities",
                        )
                    },
                    onFailure = { t ->
                        R1Log.w("BackgroundRefreshJob", "fetch failed: ${t.message}")
                    },
                )
            }
            // Always finish: this is a fire-and-forget warmup, not a transaction. Reschedule
            // on the next periodic tick rather than asking the system to retry now (which
            // would waste battery on a transient outage).
            jobFinished(params, false)
        }
        // Return true to indicate we're handling the work asynchronously; the system will
        // hold a wakelock until we call jobFinished above.
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // System told us to stop (constraint no longer met). Cancel the coroutine and
        // tell the scheduler we don't need a re-run; the next periodic tick covers it.
        inFlight?.cancel()
        inFlight = null
        return false
    }

    companion object {
        // Arbitrary fixed id — only needs to be stable across schedule/cancel calls. The
        // 0x71BA prefix is the closest valid-hex spelling of "R1HA" for greppability.
        const val JOB_ID = 0x71BA0001
        /** 15 minutes is the minimum Android allows for periodic jobs on modern API levels;
         *  shorter is clamped to 15 min by the platform anyway. */
        const val PERIOD_MS = 15L * 60L * 1000L

        /** Schedule the periodic refresh. Idempotent — replaces any existing schedule
         *  with this id. Network is required (otherwise the job runs anyway but
         *  listAllEntities would fail). */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, BackgroundRefreshJob::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                // Not persisted across reboot: setPersisted(true) requires the
                // RECEIVE_BOOT_COMPLETED permission, and building the JobInfo
                // without it throws, crashing the caller the moment background
                // refresh is enabled. The app re-schedules this job on every
                // launch, so the only cost of dropping reboot-persistence is no
                // background warming between a reboot and the next app open.
                .setPersisted(false)
                .build()
            val result = scheduler.schedule(info)
            R1Log.i(
                "BackgroundRefreshJob",
                "schedule result=$result (RESULT_SUCCESS=${JobScheduler.RESULT_SUCCESS})",
            )
        }

        /** Cancel the periodic refresh. Safe to call when nothing is scheduled. */
        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID)
            R1Log.i("BackgroundRefreshJob", "cancelled")
        }
    }
}
