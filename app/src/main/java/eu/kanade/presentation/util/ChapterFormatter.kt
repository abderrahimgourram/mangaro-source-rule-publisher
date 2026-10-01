package eu.kanade.presentation.util

/**
 * Formats chapter numbers cleanly for Arabic display.
 * Removes unnecessary .0 decimal suffixes when the chapter number is an integer,
 * while preserving meaningful fractional chapter numbers (e.g. 1.5, 17.5, 10.25, 0.5).
 */
fun Double.toCleanChapterString(): String {
    return if (this % 1.0 == 0.0) {
        this.toLong().toString()
    } else {
        this.toString().removeSuffix(".0")
    }
}

fun Float.toCleanChapterString(): String {
    return this.toDouble().toCleanChapterString()
}

fun formatChapterDisplay(chapterNumber: Double): String {
    return if (chapterNumber > 0.0) {
        "الفصل ${chapterNumber.toCleanChapterString()}"
    } else {
        "الفصل الأخير"
    }
}

fun formatChapterDisplay(chapterNumber: Float): String {
    return formatChapterDisplay(chapterNumber.toDouble())
}
