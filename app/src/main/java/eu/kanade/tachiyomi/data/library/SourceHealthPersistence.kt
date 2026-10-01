package eu.kanade.tachiyomi.data.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mihon.domain.source.health.SourceHealthMonitor

object SourceHealthPersistence {
    fun initialize(context: Context, scope: CoroutineScope) {
        val prefs = context.getSharedPreferences("source-health", Context.MODE_PRIVATE)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val sameParser = prefs.getLong("parser-build", -1) == version
        val values = prefs.all.mapNotNull { (key, raw) ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            val parts = (raw as? String)?.split(',') ?: return@mapNotNull null
            runCatching { id to SourceHealthMonitor.Health(SourceHealthMonitor.State.valueOf(parts[0]), parts[1].toInt(), parts[2].toInt(), parts[3].toLong()) }.getOrNull()
        }.toMap().mapValues { (_, health) ->
            if (sameParser) health.copy(nextProbeAt = health.nextProbeAt.coerceAtMost(System.currentTimeMillis() + 6 * 60 * 60_000L))
            else SourceHealthMonitor.Health(state = SourceHealthMonitor.State.DEGRADED)
        }
        SourceHealthMonitor.shared.restore(values)
        SourceHealthMonitor.shared.states.onEach { states ->
            val edit = prefs.edit().clear().putLong("parser-build", version)
            states.forEach { (id, health) -> edit.putString(id.toString(), "${health.state},${health.failures},${health.semanticFailures},${health.nextProbeAt}") }
            edit.apply()
        }.launchIn(scope)
    }
}
