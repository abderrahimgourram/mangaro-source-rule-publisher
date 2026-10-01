package eu.kanade.presentation.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterFormatterTest {

    @Test
    fun `test clean chapter string formatting for integers`() {
        1.0.toCleanChapterString() shouldBe "1"
        17.0.toCleanChapterString() shouldBe "17"
        100.0.toCleanChapterString() shouldBe "100"
    }

    @Test
    fun `test clean chapter string formatting for decimals`() {
        1.5.toCleanChapterString() shouldBe "1.5"
        17.5.toCleanChapterString() shouldBe "17.5"
        10.25.toCleanChapterString() shouldBe "10.25"
        0.5.toCleanChapterString() shouldBe "0.5"
    }

    @Test
    fun `test Arabic chapter display formatting`() {
        formatChapterDisplay(1.0) shouldBe "الفصل 1"
        formatChapterDisplay(17.0) shouldBe "الفصل 17"
        formatChapterDisplay(100.0) shouldBe "الفصل 100"
        formatChapterDisplay(1.5) shouldBe "الفصل 1.5"
        formatChapterDisplay(17.5) shouldBe "الفصل 17.5"
        formatChapterDisplay(10.25) shouldBe "الفصل 10.25"
        formatChapterDisplay(0.5) shouldBe "الفصل 0.5"
        formatChapterDisplay(0.0) shouldBe "الفصل الأخير"
    }
}
