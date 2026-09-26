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

    /** Static clue-style measurements, used by the difficulty score. */
    val style: Map<String, Double>

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
        style = clueStyle(
            buildMap {
                put("x", puzzle.x)
                put("y", puzzle.y)
                if (puzzle.z.isNotEmpty()) put("z", puzzle.z)
            },
        )
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
     * Arc consistency only: returns a single solution only when propagation
     * fully determines the grid (the cheap uniqueness check the generator
     * uses). Statistics are filled in for the difficulty score.
     */
    fun solvePropagationOnly(): Pair<List<Map<Cell, Char>>, SolveStats> {
        val domains = initialDomains()
        val stats = SolveStats(edge = geo.numRows, alphabetSize = alphabet.size)
        val consistent = propagate(domains)
        val remaining = domains.values.filter { it.size > 1 }
        stats.solvedByPropagation = consistent && remaining.isEmpty()
        stats.residualCells = remaining.size
        stats.residualCandidates = remaining.sumOf { it.size - 1 }
        stats.maxResidualDomain = remaining.maxOfOrNull { it.size } ?: 0
        val solutions = ArrayList<Map<Cell, Char>>()
        if (stats.solvedByPropagation) {
            val assignment = LinkedHashMap<Cell, Char>()
            for ((cell, domain) in domains) assignment[cell] = domain.first()
            verify(assignment)
            solutions.add(assignment)
        }
        measure(stats, style, DEFAULT_WEIGHTS)
        return solutions to stats
    }

    /** Enumerate up to [maxSolutions] solutions (for the full uniqueness check). */
    fun solveAll(maxSolutions: Int): List<Map<Cell, Char>> {
        val domains = initialDomains()
        if (!propagate(domains)) return emptyList()
        val solutions = ArrayList<Map<Cell, Char>>()
        nodes = 0
        search(domains, solutions, maxSolutions)
        return solutions
    }

    /**
     * Cells a clue literally writes down at a fixed position. This is a cheap
     * fixed-width AST scan ([pinnedLiterals]); it does **not** include letters
     * only implied by classes, repeats or skeletons, and needs no search.
     */
    fun givenLetters(): Map<Cell, Char> {
        val out = HashMap<Cell, Char>()
        for ((lineIndex, line) in geo.lines.withIndex()) {
            for ((offset, ch) in pinnedLiterals(compiled[lineIndex].ast)) {
                if (offset in line.cells.indices) out[line.cells[offset]] = ch
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
