package com.hexregex.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SolverTest {
    @Test
    fun `solves every fixture from scratch`() {
        for (name in Fixtures.names) {
            val puzzle = Fixtures.puzzle(name)
            val solution = Solver(puzzle).solveFromScratch()
            assertNotNull(solution, "$name should be solvable")
            assertTrue(Judge.judge(puzzle, solution!!).solved, "$name solution must pass judging")
        }
    }

    @Test
    fun `honours consistent fixed letters`() {
        for (name in Fixtures.names) {
            val puzzle = Fixtures.puzzle(name)
            val stored = puzzle.storedSolutionGrid()
            val fixed = stored.entries.take(6).associate { it.key to it.value }

            val solution = Solver(puzzle).solveWith(fixed)
            assertNotNull(solution, "$name with fixed letters should be solvable")
            for ((cell, ch) in fixed) assertEquals(ch, solution!![cell], "$name fixed $cell")
            assertTrue(Judge.judge(puzzle, solution!!).solved, "$name")
        }
    }

    @Test
    fun `detects unsatisfiable fixed letters on a synthetic puzzle`() {
        val puzzle = Puzzle.fromMap(
            mapOf(
                "kind" to "rect",
                "rows" to 2L,
                "cols" to 2L,
                "author" to "t",
                "name" to "t",
                "x" to listOf("AB", "CD"),
                "y" to listOf("AC", "BD"),
            ),
        )
        val solver = Solver(puzzle)

        val full = solver.solveFromScratch()
        assertNotNull(full)
        assertEquals('A', full!![Cell(0, 0)])
        assertEquals('C', full[Cell(0, 1)])
        assertEquals('B', full[Cell(1, 0)])
        assertEquals('D', full[Cell(1, 1)])

        assertNotNull(solver.solveWith(mapOf(Cell(0, 0) to 'A')))
        assertNull(solver.solveWith(mapOf(Cell(0, 0) to 'Z')))
    }

    @Test
    fun `every fixture clue is feasible with the full alphabet`() {
        val alphabet = ('A'..'Z').toList()
        for (name in Fixtures.names) {
            val puzzle = Fixtures.puzzle(name)
            for (line in puzzle.geometry.lines) {
                val allowed = List(line.length) { alphabet }
                assertTrue(
                    RegexEngine.feasible(allowed, RegexEngine.compile(puzzle.clue(line.family, line.index))),
                    "$name ${line.family}[${line.index}]",
                )
            }
        }
    }
}
