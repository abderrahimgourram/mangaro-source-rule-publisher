package mihon.domain.source.discovery.interactor

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.SourceCapabilities

class GetSourceCapabilities {

    operator fun invoke(source: Source): SourceCapabilities {
        val catalogueSource = source as? CatalogueSource
            ?: return SourceCapabilities(
                sourceId = source.id,
                sourceName = source.name,
                lang = source.lang,
                supportsPopular = CapabilitySupport.UNSUPPORTED,
                supportsLatest = CapabilitySupport.UNSUPPORTED,
                supportsSearch = CapabilitySupport.UNSUPPORTED,
                supportsStatusFilter = CapabilitySupport.UNSUPPORTED,
                supportsCompletedFilter = CapabilitySupport.UNSUPPORTED,
                hasFilterList = false,
            )

        val supportsPopular = CapabilitySupport.SUPPORTED
        val supportsLatest = if (catalogueSource.supportsLatest) {
            CapabilitySupport.SUPPORTED
        } else {
            CapabilitySupport.UNSUPPORTED
        }
        val supportsSearch = CapabilitySupport.SUPPORTED

        val filterList = try {
            catalogueSource.getFilterList()
        } catch (_: Exception) {
            FilterList()
        }

        val hasFilterList = filterList.isNotEmpty()
        val statusFilterResult = inspectStatusFilter(filterList)
        val supportsNewFilter = inspectNewFilter(filterList)

        return SourceCapabilities(
            sourceId = catalogueSource.id,
            sourceName = catalogueSource.name,
            lang = catalogueSource.lang,
            supportsPopular = supportsPopular,
            supportsLatest = supportsLatest,
            supportsSearch = supportsSearch,
            supportsStatusFilter = statusFilterResult.supportsStatus,
            supportsCompletedFilter = statusFilterResult.supportsCompleted,
            supportsNewFilter = supportsNewFilter,
            hasFilterList = hasFilterList,
        )
    }

    private data class StatusFilterInspection(
        val supportsStatus: CapabilitySupport,
        val supportsCompleted: CapabilitySupport,
    )

    private fun inspectStatusFilter(filterList: FilterList): StatusFilterInspection {
        if (filterList.isEmpty()) {
            return StatusFilterInspection(CapabilitySupport.UNSUPPORTED, CapabilitySupport.UNSUPPORTED)
        }

        var foundStatusFilter = false
        var foundCompletedOption = false

        for (filter in filterList) {
            val name = filter.name.lowercase()
            val isStatusName = name.contains("status") ||
                name.contains("حالة") ||
                name.contains("الوضع") ||
                name.contains("state")

            if (isStatusName) {
                foundStatusFilter = true
                if (inspectFilterForCompleted(filter)) {
                    foundCompletedOption = true
                }
            } else if (inspectFilterForCompleted(filter)) {
                foundCompletedOption = true
            }
        }

        val statusSupport = if (foundStatusFilter) CapabilitySupport.SUPPORTED else CapabilitySupport.UNSUPPORTED
        val completedSupport = if (foundCompletedOption) CapabilitySupport.SUPPORTED else CapabilitySupport.UNSUPPORTED

        return StatusFilterInspection(statusSupport, completedSupport)
    }

    private fun inspectFilterForCompleted(filter: Filter<*>): Boolean {
        return when (filter) {
            is Filter.Select<*> -> {
                filter.values.any { value ->
                    val text = value.toString().lowercase()
                    text.contains("completed") ||
                        text.contains("مكتمل") ||
                        text.contains("مكتملة") ||
                        text.contains("finish")
                }
            }
            is Filter.Group<*> -> {
                filter.state.any { item ->
                    if (item is Filter<*>) inspectFilterForCompleted(item) else false
                }
            }
            else -> false
        }
    }

    private fun inspectNewFilter(filterList: FilterList): CapabilitySupport {
        if (filterList.isEmpty()) return CapabilitySupport.UNSUPPORTED

        for (filter in filterList) {
            if (inspectFilterForNew(filter)) {
                return CapabilitySupport.SUPPORTED
            }
        }
        return CapabilitySupport.UNSUPPORTED
    }

    private fun inspectFilterForNew(filter: Filter<*>): Boolean {
        return when (filter) {
            is Filter.Select<*> -> {
                filter.values.any { value ->
                    val text = value.toString().lowercase()
                    isNewTitleValue(text)
                }
            }
            is Filter.Group<*> -> {
                filter.state.any { item ->
                    if (item is Filter<*>) inspectFilterForNew(item) else false
                }
            }
            else -> false
        }
    }

    private fun isNewTitleValue(text: String): Boolean {
        val isAddedOrCreated = text.contains("date added") ||
            text.contains("added") ||
            text.contains("newly added") ||
            text.contains("new additions") ||
            text.contains("الأحدث إضافة") ||
            text.contains("تاريخ الإضافة") ||
            text == "new" ||
            text == "newest" ||
            text == "جديد" ||
            text.contains("أحدث المانجا")

        val isChapterUpdate = text.contains("latest chapter") ||
            text.contains("أحدث الفصول") ||
            text.contains("latest update") ||
            text.contains("updated")

        return isAddedOrCreated && !isChapterUpdate
    }
}
