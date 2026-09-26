package io.github.cuso4deposit.regexcrossword.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PuzzleTest {
    @Test
    fun `CLI fixture parses and re-serialises byte for byte`() {
        for (name in Fixtures.names) {
            val original = Fixtures.text(name)
            val puzzle = Puzzle.fromJson(original)
            assertEquals(
                original,
                puzzle.toJson(),
                "round-trip mismatch for $name.json",
            )
        }
    }

    @Test
    fun `alternate files also round-trip`() {
        for (name in Fixtures.names) {
            val original = Fixtures.alternate(name)
            assertEquals(original, Puzzle.fromJson(original).toJson(), name)
        }
    }

    @Test
    fun `hex puzzle carries three clue families and an edge`() {
        val puzzle = Fixtures.puzzle("hex_medium_1000")
        assertEquals("hex", puzzle.kind)
        assertEquals(5, puzzle.edge)
        assertEquals(27, puzzle.x.size + puzzle.y.size + puzzle.z.size)
        assertNotNull(puzzle.solution)
    }

    @Test
    fun `rect puzzle carries two clue families`() {
        val puzzle = Fixtures.puzzle("rect_easy_1000")
        assertEquals("rect", puzzle.kind)
        assertEquals(5, puzzle.rows)
        assertEquals(5, puzzle.cols)
        assertEquals(10, puzzle.x.size + puzzle.y.size)
        assertTrue(puzzle.z.isEmpty())
    }

    @Test
    fun `stored solution covers every cell exactly once`() {
        for (name in Fixtures.names) {
            val puzzle = Fixtures.puzzle(name)
            val grid = puzzle.storedSolutionGrid()
            assertEquals(puzzle.geometry.cellCount(), grid.size, name)
        }
    }
}
