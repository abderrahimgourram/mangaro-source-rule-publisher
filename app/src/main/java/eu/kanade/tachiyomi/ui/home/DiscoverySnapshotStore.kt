package eu.kanade.tachiyomi.ui.home

import mihon.domain.source.discovery.model.DiscoveryCategory
import java.util.concurrent.ConcurrentHashMap

object DiscoverySnapshotStore {

    private val snapshotMap = ConcurrentHashMap<DiscoveryCategory, List<HomeDiscoveryItem>>()

    fun setSnapshot(category: DiscoveryCategory, items: List<HomeDiscoveryItem>) {
        if (items.isNotEmpty()) {
            snapshotMap[category] = items.toList()
        }
    }

    fun getSnapshot(category: DiscoveryCategory): List<HomeDiscoveryItem> {
        return snapshotMap[category]?.filter { mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(it.sourceId) } ?: emptyList()
    }

    fun clear() {
        snapshotMap.clear()
    }
}
