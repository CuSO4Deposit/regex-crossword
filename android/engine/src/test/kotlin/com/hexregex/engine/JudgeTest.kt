package com.hexregex.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JudgeTest {
    /** A single-cell mutation that is known (computed with the CLI) to break a line. */
    private data class Mutation(val cell: Cell, val original: Char, val replacement: Char)

    private val knownMutations = mapOf(
        "rect_easy_1000" to Mutation(Cell(0, 0), 'Y', 'A'),
        "hex_medium_1000" to Mutation(Cell(0, 1), 'N', 'A'),
        "hex_hard_1000" to Mutation(Cell(0, 1), 'N', 'A'),
    )

    @Test
    fun `the stored solution passes every line`() {
        for (name in Fixtures.names) {
            val puzzle = Fixtures.puzzle(name)
            val result = Judge.judge(puzzle, puzzle.storedSolutionGrid())
            assertTrue(result.solved, "$name: stored solution should satisfy all clues")
            assertTrue(result.complete, "$name: grid should be complete")
        }
    }

    @Test
    fun `changing one cell is detected`() {
        for (name in Fixtures.names) {
            val puzzle = Fixtures.puzzle(name)
            val mutation = knownMutations.getValue(name)
            val grid = puzzle.storedSolutionGrid().toMutableMap()
            assertEquals(mutation.original, grid[mutation.cell], "$name sanity")
            grid[mutation.cell] = mutation.replacement

            val result = Judge.judge(puzzle, grid)
            assertFalse(result.solved, "$name: a single wrong cell must not pass")
            assertTrue(result.failures.isNotEmpty(), "$name: expected failing lines")
        }
    }

    /**
     * The constructive puzzles are not unique. A different valid grid must be
     * accepted: proof that judging does not compare against the stored answer.
     */
    @Test
    fun `an alternate valid solution is accepted`() {
        for (name in Fixtures.names) {
            val puzzle = Fixtures.puzzle(name)
            val alternate = Puzzle.fromJson(Fixtures.alternate(name))

            val originalGrid = puzzle.storedSolutionGrid()
            val alternateGrid = alternate.storedSolutionGrid()
            assertNotEquals(
                originalGrid,
                alternateGrid,
                "$name: alternate must actually differ from the stored solution",
            )

            val result = Judge.judge(puzzle, alternateGrid)
            assertTrue(
                result.solved,
                "$name: alternate valid solution must be accepted",
            )
        }
    }

    @Test
    fun `an empty grid is incomplete and not solved`() {
        val puzzle = Fixtures.puzzle("hex_medium_1000")
        val result = Judge.judge(puzzle, emptyMap())
        assertFalse(result.complete)
        assertFalse(result.solved)
    }

    @Test
    fun `a partially filled grid reports incompleteness`() {
        val puzzle = Fixtures.puzzle("hex_medium_1000")
        val grid = puzzle.storedSolutionGrid().toMutableMap()
        grid.remove(Cell(4, 4))
        val result = Judge.judge(puzzle, grid)
        assertFalse(result.complete)
        assertFalse(result.solved)
    }
}
