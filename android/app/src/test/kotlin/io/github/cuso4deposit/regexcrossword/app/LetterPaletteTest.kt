package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterPaletteTest {
    @Test
    fun keyWidthsFillTheAvailableWidth() {
        assertEquals(30.8f, paletteKeyWidth(344.dp, 4.dp, 10).value, 0.001f)
        assertEquals(16.4f, paletteKeyWidth(200.dp, 4.dp, 10).value, 0.001f)
    }

    @Test
    fun keyWidthsAreCappedOnWideScreens() {
        assertEquals(48f, paletteKeyWidth(1000.dp, 4.dp, 10).value, 0.001f)
    }

    @Test
    fun noColumnsMeansNoKeyWidth() {
        assertEquals(0f, paletteKeyWidth(344.dp, 4.dp, 0).value, 0.001f)
    }

    @Test
    fun staggeredKeysKeepAKeyboardSizedHeight() {
        assertTrue(StaggeredKeyHeight >= 48.dp)
    }
}
