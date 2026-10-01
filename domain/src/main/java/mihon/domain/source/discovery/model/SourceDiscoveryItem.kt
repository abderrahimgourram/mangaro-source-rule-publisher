package mihon.domain.source.discovery.model

data class SourceDiscoveryItem(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val status: Int = 0,
    val genre: List<String> = emptyList(),
    val localMangaId: Long? = null,
)
