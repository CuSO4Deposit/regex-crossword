package io.github.cuso4deposit.regexcrossword.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Proves on-device generation is byte-identical to `hexregex gen`. */
class GeneratorTest {
    private data class Case(val difficulty: String, val edge: Int, val seed: Int)

    private val cases = listOf(
        Case("easy", 5, 1000),
        Case("easy", 5, 1001),
        Case("easy", 5, 1002),
        Case("medium", 5, 1000),
        Case("medium", 5, 1001),
        Case("medium", 5, 1002),
        Case("medium", 5, 12345),
        Case("hard", 5, 1000),
        Case("hard", 5, 1001),
    )

    private fun resource(path: String): String =
        Fixtures::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("missing resource $path")

    @Test
    fun `constructive generation matches the CLI byte for byte`() {
        for (case in cases) {
            val kind = if (case.difficulty == "easy") "rect" else "hex"
            val name = "${kind}_${case.difficulty}_${case.edge}_${case.seed}.json"
            val expected = resource("/generated/$name")
            val puzzle = Generator.constructive(
                GenConfig(edge = case.edge, difficulty = case.difficulty, seed = case.seed),
            )
            assertEquals(expected, puzzle.toJson(), name)
        }
    }

    @Test
    fun `generated puzzles pass line-by-line judging`() {
        for (case in cases) {
            val puzzle = Generator.constructive(
                GenConfig(edge = case.edge, difficulty = case.difficulty, seed = case.seed),
            )
            val grid = puzzle.storedSolutionGrid()
            assertEquals(puzzle.geometry.cellCount(), grid.size, "${case.difficulty}/${case.seed}")
            org.junit.jupiter.api.Assertions.assertTrue(
                Judge.judge(puzzle, grid).solved,
                "${case.difficulty}/${case.seed}",
            )
        }
    }
}
