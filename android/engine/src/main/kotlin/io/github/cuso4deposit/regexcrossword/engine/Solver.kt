package io.github.cuso4deposit.regexcrossword.engine

import java.util.ArrayDeque
import java.util.regex.Pattern

/** Thrown when the search exceeds its node budget (pathological clue). */
class SolverLimitException(message: String) : RuntimeException(message)

/**
 * Constraint solver for regular crosswords: arc-consistency propagation plus
 * MRV backtracking, verified with whole-string Java regex matching.
 *
 * Direct port of `hexregex/solver.py`. On device it is used to find a
 * completion consistent with the player's current grid (hints), never to
 * compare against a stored answer.
 */
class Solver(
    val puzzle: Puzzle,
    val alphabet: List<Char> = ('A'..'Z').toList(),
    private val nodeLimit: Int = 2_000_000,
) {
    val geo: Geometry = puzzle.geometry

    private val compiled: List<Compiled>
    private val lineCells: List<List<Cell>>
    private val clueOf: List<String>
    private val cellLinesMap: Map<Cell, List<Int>>

    init {
        require(alphabet.isNotEmpty()) { "alphabet must not be empty" }
        val comp = ArrayList<Compiled>()
        val cells = ArrayList<List<Cell>>()
        val clues = ArrayList<String>()
        val map = HashMap<Cell, MutableList<Int>>()
        for ((lineIndex, line) in geo.lines.withIndex()) {
            val text = puzzle.clue(line.family, line.index)
            comp.add(RegexEngine.compile(text))
            cells.add(line.cells)
            clues.add(text)
            for (cell in line.cells) map.getOrPut(cell) { mutableListOf() }.add(lineIndex)
        }
        compiled = comp
        lineCells = cells
        clueOf = clues
        cellLinesMap = map
    }

    private var nodes = 0

    fun initialDomains(): LinkedHashMap<Cell, MutableSet<Char>> {
        val domains = LinkedHashMap<Cell, MutableSet<Char>>()
        for (cell in geo.cells()) domains[cell] = alphabet.toMutableSet()
        return domains
    }

    /** Arc consistency to a fixed point; false on wipe-out. */
    fun propagate(domains: MutableMap<Cell, MutableSet<Char>>): Boolean {
        val lineCount = geo.lines.size
        val queue = ArrayDeque<Int>()
        val inQueue = BooleanArray(lineCount) { true }
        for (i in 0 until lineCount) queue.add(i)
        while (queue.isNotEmpty()) {
            val lineId = queue.poll()
            inQueue[lineId] = false
            val changed = revise(lineId, domains)
            if (lineCells[lineId].any { domains.getValue(it).isEmpty() }) return false
            if (changed) {
                for (cell in lineCells[lineId]) {
                    for (other in cellLinesMap.getValue(cell)) {
                        if (!inQueue[other]) {
                            queue.add(other)
                            inQueue[other] = true
                        }
                    }
                }
            }
        }
        return true
    }

    private fun revise(lineId: Int, domains: MutableMap<Cell, MutableSet<Char>>): Boolean {
        val cells = lineCells[lineId]
        val pattern = compiled[lineId]
        val allowed = MutableList(cells.size) { domains.getValue(cells[it]).toSet() }
        var changed = false
        for (position in cells.indices) {
            val cell = cells[position]
            val domain = domains.getValue(cell)
            val old = allowed[position]
            val keep = ArrayList<Char>()
            for (ch in domain) {
                allowed[position] = setOf(ch)
                if (RegexEngine.feasibleCached(allowed, pattern)) keep.add(ch)
            }
            allowed[position] = old
            if (keep.size != domain.size) {
                domains[cell] = keep.toMutableSet()
                changed = true
                if (keep.isEmpty()) return true
            }
        }
        return changed
    }

    private fun mrvCell(domains: Map<Cell, Set<Char>>): Cell? {
        var best: Cell? = null
        var bestSize = Int.MAX_VALUE
        for ((cell, domain) in domains) {
            val size = domain.size
            if (size in 2 until bestSize) {
                bestSize = size
                best = cell
                if (size == 2) break
            }
        }
        return best
    }

    private fun search(
        domains: LinkedHashMap<Cell, MutableSet<Char>>,
        solutions: MutableList<Map<Cell, Char>>,
        maxSolutions: Int,
    ) {
        nodes++
        if (nodes > nodeLimit) throw SolverLimitException("search exceeded $nodeLimit nodes")
        val cell = mrvCell(domains)
        if (cell == null) {
            val solution = LinkedHashMap<Cell, Char>()
            for ((c, d) in domains) solution[c] = d.first()
            verify(solution)
            solutions.add(solution)
            return
        }
        for (ch in domains.getValue(cell).sorted()) {
            val branch = LinkedHashMap<Cell, MutableSet<Char>>()
            for ((c, d) in domains) branch[c] = d.toMutableSet()
            branch[cell] = mutableSetOf(ch)
            if (propagate(branch)) {
                search(branch, solutions, maxSolutions)
                if (solutions.size >= maxSolutions) return
            }
        }
    }

    /**
     * Find one completion consistent with [assignments] (empty = solve from
     * scratch), or `null` when no completion exists.
     */
    fun solveWith(assignments: Map<Cell, Char>): Map<Cell, Char>? {
        val domains = initialDomains()
        for ((cell, ch) in assignments) {
            val domain = domains[cell] ?: return null
            if (ch !in alphabet) return null
            domain.clear()
            domain.add(ch)
        }
        if (!propagate(domains)) return null
        val solutions = ArrayList<Map<Cell, Char>>()
        nodes = 0
        search(domains, solutions, maxSolutions = 1)
        return solutions.firstOrNull()
    }

    fun solveFromScratch(): Map<Cell, Char>? = solveWith(emptyMap())

    /**
     * Letters forced by a *single* line: cells whose feasible letter set over
     * one clue is a singleton. These are the "givens" a player can fill just
     * by reading one clue, without cross-referencing.
     */
    fun givenLetters(): Map<Cell, Char> {
        val full = alphabet.toSet()
        val out = HashMap<Cell, Char>()
        for ((lineIndex, line) in geo.lines.withIndex()) {
            val pattern = compiled[lineIndex]
            val cells = line.cells
            val base = List(cells.size) { full }
            for (i in cells.indices) {
                val keep = ArrayList<Char>()
                for (ch in alphabet) {
                    val allowed = base.toMutableList()
                    allowed[i] = setOf(ch)
                    if (RegexEngine.feasibleCached(allowed, pattern)) keep.add(ch)
                }
                if (keep.size == 1) {
                    val cell = cells[i]
                    val ch = keep[0]
                    val prev = out[cell]
                    when {
                        prev == null -> out[cell] = ch
                        prev != ch -> out.remove(cell)
                    }
                }
            }
        }
        return out
    }

    /** Re-check a complete solution with whole-string Java regex matching. */
    fun verify(solution: Map<Cell, Char>) {
        for ((lineIndex, line) in geo.lines.withIndex()) {
            val word = buildString { for (cell in line.cells) append(solution.getValue(cell)) }
            if (!Pattern.compile(clueOf[lineIndex]).matcher(word).matches()) {
                throw IllegalStateException(
                    "internal error: $word does not match ${line.family}[${line.index}] = ${clueOf[lineIndex]}",
                )
            }
        }
    }

    fun solutionRows(solution: Map<Cell, Char>): List<List<String>> =
        (0 until geo.numRows).map { r ->
            (0 until geo.rowSize(r)).map { c -> solution.getValue(Cell(r, c)).toString() }
        }
}
