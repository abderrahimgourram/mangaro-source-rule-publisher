package eu.kanade.tachiyomi.extension

object DefaultArabicSources {
    data class RecommendedSource(
        val pkgName: String,
        val name: String,
        val englishName: String,
        val lang: String = "ar",
    )

    val SOURCES = listOf(
        RecommendedSource(
            pkgName = "eu.kanade.tachiyomi.extension.ar.azora",
            name = "Azora",
            englishName = "Azora",
        ),
        RecommendedSource(
            pkgName = "eu.kanade.tachiyomi.extension.ar.hijala",
            name = "Hijala",
            englishName = "Hijala",
        ),
        RecommendedSource(
            pkgName = "eu.kanade.tachiyomi.extension.ar.mangadar",
            name = "MangaDar",
            englishName = "MangaDar",
        ),
        RecommendedSource(
            pkgName = "eu.kanade.tachiyomi.extension.ar.mangatime",
            name = "MangaTime",
            englishName = "MangaTime",
        ),
        RecommendedSource(
            pkgName = "eu.kanade.tachiyomi.extension.ar.teamx",
            name = "Team X",
            englishName = "Team X",
        ),
        RecommendedSource(
            pkgName = "eu.kanade.tachiyomi.extension.ar.mangalek",
            name = "مانجا ليك",
            englishName = "MangaLek",
        ),
    )

    val PACKAGE_NAMES = SOURCES.map { it.pkgName }.toSet()

    fun isRecommended(pkgName: String): Boolean {
        val basePkg = pkgName.substringBefore("-")
        return basePkg in PACKAGE_NAMES || pkgName in PACKAGE_NAMES
    }
}
