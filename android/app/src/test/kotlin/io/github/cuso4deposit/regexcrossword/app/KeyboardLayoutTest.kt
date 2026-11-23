package io.github.cuso4deposit.regexcrossword.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {
    @Test
    fun everyStaggeredLayoutContainsEachLetterOnce() {
        for (layout in KeyboardLayout.entries.filterNot { it.wrapped }) {
            val letters = layout.rows.joinToString("")
            assertEquals("$layout letter count", 26, letters.length)
            assertEquals("$layout letters", ('A'..'Z').toSet(), letters.toSet())
            assertTrue(
                "$layout row width",
                layout.rows.all { it.length <= KeyboardLayout.MAX_COLUMNS },
            )
        }
    }

    @Test
    fun qwertyUsesTheStandardStagger() {
        assertEquals(listOf(10, 9, 7), KeyboardLayout.QWERTY.rows.map { it.length })
    }

    @Test
    fun alphabeticalKeepsTheWrappingPalette() {
        assertTrue(KeyboardLayout.ALPHABETICAL.wrapped)
        assertEquals(KeyboardLayout.ALPHABETICAL, KeyboardLayout.default)
    }
}
