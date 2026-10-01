package eu.kanade.tachiyomi.ui.home

object GroupDiscoveryItems {

    fun normalizeTitle(title: String): String {
        return title
            .trim()
            .lowercase()
            // Remove Arabic diacritics / tashkeel (\u064B-\u0652)
            .replace(Regex("[\\u064B-\\u0652]"), "")
            // Remove Arabic Tatweel / kashida (\u0640)
            .replace("\u0640", "")
            // Normalize Alef variants (أ, إ, آ -> ا)
            .replace(Regex("[أإآ]"), "ا")
            // Normalize Teh Marbuta (ة -> ه)
            .replace("ة", "ه")
            // Normalize Yeh / Alef Maksura (ى -> ي)
            .replace("ى", "ي")
            // Replace dashes, underscores, dots, and punctuation with spaces
            .replace(Regex("[-_.,:;!?\"'’`()~*\\[\\]{}|/\\\\]"), " ")
            // Collapse multiple spaces into single space
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun group(items: List<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
        if (items.isEmpty()) return emptyList()

        val groupedMap = LinkedHashMap<String, MutableList<HomeDiscoveryItem>>()

        for (item in items) {
            val key = normalizeTitle(item.title)
            val list = groupedMap.getOrPut(key) { mutableListOf() }
            if (list.none { it.sourceId == item.sourceId && (it.mangaId == item.mangaId || (it.url.isNotEmpty() && it.url == item.url)) }) {
                list.add(item)
            }
        }

        return groupedMap.values.map { group ->
            val first = group.first()
            val versions = group.map {
                HomeSourceVersion(
                    mangaId = it.mangaId,
                    sourceId = it.sourceId,
                    sourceName = it.sourceName,
                    url = it.url,
                    title = it.title,
                )
            }.distinctBy { it.sourceId }

            first.copy(availableVersions = versions)
        }
    }
}
