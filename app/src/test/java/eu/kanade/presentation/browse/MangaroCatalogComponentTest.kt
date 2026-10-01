package eu.kanade.presentation.browse

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.home.MangaroVisualTokens
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryDisplayMode

class MangaroCatalogComponentTest {

    @Test
    fun `verify all display modes deserialize and serialize accurately`() {
        val modes = listOf(
            LibraryDisplayMode.ComfortableGrid,
            LibraryDisplayMode.CompactGrid,
            LibraryDisplayMode.CoverOnlyGrid,
            LibraryDisplayMode.List,
        )

        modes.forEach { mode ->
            val serialized = mode.serialize()
            val deserialized = LibraryDisplayMode.deserialize(serialized)
            deserialized shouldBe mode
        }

        LibraryDisplayMode.values shouldContainAll modes
    }

    @Test
    fun `verify Mangaro visual tokens for catalog components`() {
        MangaroVisualTokens.CardCornerRadius shouldBe 14.dp
        MangaroVisualTokens.SurfaceDark shouldBe Color(0xFF18121D)
        MangaroVisualTokens.CardBorderColor shouldBe androidx.compose.ui.graphics.Color(0x33A78BFA)
    }
}
