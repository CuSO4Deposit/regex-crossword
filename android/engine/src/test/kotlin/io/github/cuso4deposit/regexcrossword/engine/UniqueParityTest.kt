package io.github.cuso4deposit.regexcrossword.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The Kotlin unique generator must match the Python reference byte for byte. */
class UniqueParityTest {
    private fun resource(name: String): String =
        Fixtures::class.java.getResourceAsStream("/generated/$name")?.bufferedReader()?.readText()
            ?: error("missing resource $name")

    @Test
    fun `unique generation matches the Python reference`() {
        for (seed in listOf(3_000_000, 3_000_001)) {
            val expected = resource("unique_hard_5_${seed}_t76.json")
            val puzzle = Generator.unique(
                GenConfig(
                    edge = 5,
                    difficulty = "hard",
                    seed = seed,
                    allowBackref = true,
                    targetScore = 76.0,
                ),
            )
            assertEquals(expected, puzzle.toJson(), "seed $seed")
        }
    }
}
