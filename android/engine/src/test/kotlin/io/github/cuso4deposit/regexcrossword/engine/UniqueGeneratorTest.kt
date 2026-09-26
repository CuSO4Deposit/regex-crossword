package io.github.cuso4deposit.regexcrossword.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The total unique-generator: any seed yields a unique, valid puzzle. */
class UniqueGeneratorTest {
    private fun solve(cfg: GenConfig): Puzzle =
        Generator.unique(cfg)

    @Test
    fun `hard generation yields a unique valid puzzle`() {
        for (seed in listOf(3_000_000, 3_000_001)) {
            val puzzle = solve(
                GenConfig(edge = 5, difficulty = "hard", seed = seed, allowBackref = true),
            )
            val solutions = Solver(puzzle).solveAll(2)
            assertEquals(1, solutions.size, "seed $seed should be unique")
            assertTrue(
                Judge.judge(puzzle, puzzle.storedSolutionGrid()).solved,
                "seed $seed stored solution must satisfy every clue",
            )
        }
    }

    @Test
    fun `generation is deterministic`() {
        val cfg = GenConfig(edge = 5, difficulty = "hard", seed = 3_000_000, allowBackref = true)
        assertEquals(solve(cfg).toJson(), solve(cfg).toJson())
    }
}
