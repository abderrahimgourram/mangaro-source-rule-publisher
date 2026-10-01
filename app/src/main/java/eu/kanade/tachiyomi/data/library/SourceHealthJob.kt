package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.*
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.source.discovery.interactor.GetSourceDiscovery
import mihon.domain.source.discovery.model.DiscoveryCategory
import mihon.domain.source.health.SourceHealthMonitor
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/** Each source owns its breaker/backoff. A failed source never makes WorkManager retry the batch. */
class SourceHealthJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = Injekt.get<SourceManager>()
        if (withTimeoutOrNull(30_000) { manager.isInitialized.first { it } } == null) return Result.retry()
        val hidden = Injekt.get<eu.kanade.domain.source.service.SourcePreferences>().disabledSources.get()
        val discovery = GetSourceDiscovery()
        supervisorScope {
            manager.getOnlineSources().filter { it.id.toString() !in hidden && SourceHealthMonitor.shared.due(it.id) }.map { source ->
                async {
                    try { discovery(source, DiscoveryCategory.POPULAR) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { SourceHealthMonitor.shared.failure(source.id, true) }
                }
            }.awaitAll()
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            context.workManager.enqueueUniquePeriodicWork("source-health", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SourceHealthJob>(1, TimeUnit.HOURS)
                    .setInitialDelay(1, TimeUnit.HOURS).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
            context.workManager.enqueueUniqueWork("source-health-startup", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SourceHealthJob>().setConstraints(constraints)
                    .setInitialDelay(2, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
        }
    }
}
