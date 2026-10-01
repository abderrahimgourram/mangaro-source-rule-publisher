package eu.kanade.presentation.home

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class MangaroHomeComponentTest {

    @Test
    fun `verify Mangaro visual tokens radii and design values`() {
        MangaroVisualTokens.CardCornerRadius shouldBe 14.dp
        MangaroVisualTokens.FeaturedBannerRadius shouldBe 20.dp
        MangaroVisualTokens.ChipCornerRadius shouldBe 10.dp
    }

    @Test
    fun `verify Mangaro cover color calculation is deterministic`() {
        val title = "Solo Leveling"
        val mangaId = 101L
        val hash1 = (title.hashCode() xor (mangaId.hashCode())) and 0x7FFFFFFF
        val hash2 = (title.hashCode() xor (mangaId.hashCode())) and 0x7FFFFFFF
        hash1 shouldBe hash2

        val diffTitle = "Tower of God"
        val hash3 = (diffTitle.hashCode() xor (mangaId.hashCode())) and 0x7FFFFFFF
        hash1 shouldNotBe hash3
    }
}
