package com.hexregex.engine

/**
 * A grid coordinate: row `r` top to bottom, column `c` left to right.
 *
 * This mirrors `hexregex.geometry.Cell` exactly and is a value type so it can
 * be used as a map key.
 */
data class Cell(val r: Int, val c: Int)

/** A crossword line: the ordered [cells] in **reading direction**. */
class Line(val family: String, val index: Int, val cells: List<Cell>) {
    val length: Int get() = cells.size
}

/**
 * Shared interface for the 2D rectangle and the 3D hexagon.
 *
 * Reading directions follow `hexregex/geometry.py`:
 *  - Y = whole row, left to right;
 *  - Z = top to bottom, `c = i - max(0, mid - r)`;
 *  - X = bottom to top, `c = i - max(0, r - mid)`, list reversed.
 *  - Rect: Y = row left to right, X = column top to bottom.
 */
interface Geometry {
    val kind: String
    val families: List<String>
    val lines: List<Line>
    val numRows: Int

    fun rowSize(r: Int): Int
    fun cells(): List<Cell>
    fun cellCount(): Int
    fun line(family: String, index: Int): Line
    fun validate()

    /** Every line that contains [cell] (one per family). */
    fun linesForCell(cell: Cell): List<Line> = lines.filter { cell in it.cells }
}

/** Hexagon of edge `n`: `size = 2n-1` rows, `3n(n-1)+1` cells. */
class HexGeometry(val edge: Int) : Geometry {
    init {
        require(edge >= 1) { "edge must be >= 1" }
    }

    override val kind: String = "hex"
    override val families: List<String> = listOf("x", "y", "z")
    val size: Int = 2 * edge - 1
    val mid: Int = edge - 1

    private val xLines: List<List<Cell>> = buildX()
    private val yLines: List<List<Cell>> = buildY()
    private val zLines: List<List<Cell>> = buildZ()

    override val lines: List<Line> = buildList {
        for ((family, table) in listOf("x" to xLines, "y" to yLines, "z" to zLines)) {
            for ((index, cells) in table.withIndex()) add(Line(family, index, cells))
        }
    }

    override val numRows: Int get() = size

    override fun rowSize(r: Int): Int = edge + minOf(r, size - 1 - r)

    override fun cells(): List<Cell> {
        val out = ArrayList<Cell>(cellCount())
        for (row in 0 until size) for (c in 0 until rowSize(row)) out.add(Cell(row, c))
        return out
    }

    override fun cellCount(): Int = 3 * edge * (edge - 1) + 1

    private fun buildY(): List<List<Cell>> =
        (0 until size).map { r -> (0 until rowSize(r)).map { c -> Cell(r, c) } }

    private fun buildZ(): List<List<Cell>> = (0 until size).map { i ->
        val cells = ArrayList<Cell>()
        for (r in 0 until size) {
            val c = i - maxOf(0, mid - r)
            if (c in 0 until rowSize(r)) cells.add(Cell(r, c))
        }
        cells
    }

    private fun buildX(): List<List<Cell>> = (0 until size).map { i ->
        val cells = ArrayList<Cell>()
        for (r in 0 until size) {
            val c = i - maxOf(0, r - mid)
            if (c in 0 until rowSize(r)) cells.add(Cell(r, c))
        }
        cells.reverse() // X clues read bottom to top
        cells
    }

    /** Reverse lookup: cell -> (x, y, z) line indices. */
    fun lineIndices(cell: Cell): Triple<Int, Int, Int> {
        val (r, c) = cell
        return Triple(
            c + maxOf(0, r - mid),
            r,
            c + maxOf(0, mid - r),
        )
    }

    fun xLine(i: Int): Line = lines[i]
    fun yLine(i: Int): Line = lines[size + i]
    fun zLine(i: Int): Line = lines[2 * size + i]

    override fun line(family: String, index: Int): Line = when (family) {
        "x" -> xLine(index)
        "y" -> yLine(index)
        else -> zLine(index)
    }

    override fun validate() {
        val all = cells()
        check(all.size == cellCount()) { "cell count mismatch" }
        val seen = HashMap<Cell, MutableSet<String>>()
        for (cell in all) seen[cell] = mutableSetOf()
        for (line in lines) for (cell in line.cells) seen.getValue(cell).add(line.family)
        for ((cell, fams) in seen) {
            check(fams == setOf("x", "y", "z")) { "cell $cell on $fams" }
        }
    }
}

/** Plain 2D rectangle: X = columns, Y = rows. */
class RectGeometry(val rows: Int, val cols: Int) : Geometry {
    init {
        require(rows >= 1 && cols >= 1) { "rows and cols must be >= 1" }
    }

    override val kind: String = "rect"
    override val families: List<String> = listOf("x", "y")
    override val lines: List<Line>

    init {
        val y = (0 until rows).map { r -> (0 until cols).map { c -> Cell(r, c) } }
        val x = (0 until cols).map { c -> (0 until rows).map { r -> Cell(r, c) } }
        lines = buildList {
            for ((index, cells) in y.withIndex()) add(Line("y", index, cells))
            for ((index, cells) in x.withIndex()) add(Line("x", index, cells))
        }
    }

    override val numRows: Int get() = rows

    override fun rowSize(r: Int): Int = cols

    override fun cells(): List<Cell> {
        val out = ArrayList<Cell>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) out.add(Cell(r, c))
        return out
    }

    override fun cellCount(): Int = rows * cols

    override fun line(family: String, index: Int): Line =
        if (family == "y") lines[index] else lines[rows + index]

    override fun validate() {
        val all = cells()
        check(all.size == cellCount()) { "cell count mismatch" }
        val seen = HashMap<Cell, MutableSet<String>>()
        for (cell in all) seen[cell] = mutableSetOf()
        for (line in lines) for (cell in line.cells) seen.getValue(cell).add(line.family)
        for ((cell, fams) in seen) {
            check(fams == setOf("x", "y")) { "cell $cell on $fams" }
        }
    }
}
