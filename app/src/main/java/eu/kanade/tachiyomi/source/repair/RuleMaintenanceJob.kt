package eu.kanade.tachiyomi.source.repair

import android.content.Context
import androidx.work.*
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class RuleMaintenanceJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = Injekt.get<SourceManager>()
        if (withTimeoutOrNull(30_000) { manager.isInitialized.first { it } } == null) return Result.retry()
        val engine = Injekt.get<RuleRepairEngine>()
        val disabled = Injekt.get<eu.kanade.domain.source.service.SourcePreferences>().disabledSources.get()
        supervisorScope {
            manager.getOnlineSources().filterIsInstance<RepairableSource>().filter { it.id.toString() !in disabled }.map { source ->
                async {
                    try { engine.check(source.original) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Separate feed failure never changes source health. */ }
                }
            }.awaitAll()
        }
        return Result.success()
    }
    companion object {
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        fun schedule(context: Context) {
            context.workManager.enqueueUniquePeriodicWork("source-rule-maintenance", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RuleMaintenanceJob>(30, TimeUnit.MINUTES).setInitialDelay(30, TimeUnit.MINUTES)
                    .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
        }
        fun enqueue(context: Context) {
            context.workManager.enqueueUniqueWork("source-rule-lifecycle", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RuleMaintenanceJob>().setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build())
        }
    }
}
