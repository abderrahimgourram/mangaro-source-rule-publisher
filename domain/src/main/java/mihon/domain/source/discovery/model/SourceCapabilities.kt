package mihon.domain.source.discovery.model

data class SourceCapabilities(
    val sourceId: Long,
    val sourceName: String,
    val lang: String,
    val supportsPopular: CapabilitySupport = CapabilitySupport.SUPPORTED,
    val supportsLatest: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val supportsSearch: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val supportsStatusFilter: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val supportsCompletedFilter: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val supportsNewFilter: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val hasFilterList: Boolean = false,
)
