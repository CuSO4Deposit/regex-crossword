package com.hexregex.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.hexregex.engine.Cell
import com.hexregex.engine.Geometry
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Screen-space layout for a puzzle board.
 *
 * Hexagons are drawn pointy-top on a proper honeycomb lattice: row `r` is
 * centred horizontally, consecutive rows are offset by half a cell, which is
 * exactly the shift the Python reading-direction rules imply.
 */
class BoardLayout private constructor(
    val isHex: Boolean,
    val centers: Map<Cell, Offset>,
    val size: Size,
    val textSize: Float,
    private val radius: Float,
    private val cellW: Float,
    private val cellH: Float,
    private val padding: Float,
) {
    fun polygon(cell: Cell): List<Offset> {
        val center = centers[cell] ?: return emptyList()
        return if (isHex) {
            // pointy-top hexagon
            (0 until 6).map { i ->
                val angle = Math.toRadians(60.0 * i - 90.0)
                Offset(
                    center.x + radius * cos(angle).toFloat(),
                    center.y + radius * sin(angle).toFloat(),
                )
            }
        } else {
            val halfW = cellW / 2f
            val halfH = cellH / 2f
            listOf(
                Offset(center.x - halfW, center.y - halfH),
                Offset(center.x + halfW, center.y - halfH),
                Offset(center.x + halfW, center.y + halfH),
                Offset(center.x - halfW, center.y + halfH),
            )
        }
    }

    /** Which cell (if any) is under [point]. */
    fun hitTest(point: Offset): Cell? {
        if (centers.isEmpty()) return null
        if (!isHex) {
            val c = ((point.x - padding) / cellW).toInt()
            val r = ((point.y - padding) / cellH).toInt()
            return centers.keys.firstOrNull { it.r == r && it.c == c }
        }
        var best: Cell? = null
        var bestDistance = Float.MAX_VALUE
        for ((cell, center) in centers) {
            val dx = center.x - point.x
            val dy = center.y - point.y
            val d = dx * dx + dy * dy
            if (d < bestDistance) {
                bestDistance = d
                best = cell
            }
        }
        val limit = radius * 1.25f
        return if (best != null && bestDistance <= limit * limit) best else null
    }

    companion object {
        fun of(geometry: Geometry, available: Size, padding: Float): BoardLayout {
            return if (geometry.kind == "hex") hex(geometry, available, padding)
            else rect(geometry, available, padding)
        }

        private fun hex(geometry: Geometry, available: Size, padding: Float): BoardLayout {
            val rows = geometry.numRows
            val widthCells = (0 until rows).maxOf { geometry.rowSize(it) }
            val innerW = (available.width - 2 * padding).coerceAtLeast(1f)
            val innerH = (available.height - 2 * padding).coerceAtLeast(1f)

            // boardWidth = widthCells * sqrt(3) * R ; boardHeight = ((rows-1)*1.5 + 2) * R
            val rByWidth = innerW / (widthCells * sqrt(3f))
            val rByHeight = innerH / ((rows - 1) * 1.5f + 2f)
            val radius = min(rByWidth, rByHeight)
            val cellW = sqrt(3f) * radius
            val cellH = 1.5f * radius

            val centers = HashMap<Cell, Offset>()
            for (r in 0 until rows) {
                val offset = (widthCells - geometry.rowSize(r)) / 2f
                for (c in 0 until geometry.rowSize(r)) {
                    centers[Cell(r, c)] = Offset(
                        padding + (offset + c + 0.5f) * cellW,
                        padding + radius + r * cellH,
                    )
                }
            }
            val boardW = widthCells * cellW + 2 * padding
            val boardH = ((rows - 1) * cellH + 2 * radius) + 2 * padding
            return BoardLayout(
                isHex = true,
                centers = centers,
                size = Size(boardW, boardH),
                textSize = radius * 0.95f,
                radius = radius,
                cellW = cellW,
                cellH = cellH,
                padding = padding,
            )
        }

        private fun rect(geometry: Geometry, available: Size, padding: Float): BoardLayout {
            val rows = geometry.numRows
            val cols = (0 until rows).maxOf { geometry.rowSize(it) }
            val innerW = (available.width - 2 * padding).coerceAtLeast(1f)
            val innerH = (available.height - 2 * padding).coerceAtLeast(1f)
            val cell = min(innerW / cols, innerH / rows)

            val centers = HashMap<Cell, Offset>()
            for (r in 0 until rows) {
                for (c in 0 until geometry.rowSize(r)) {
                    centers[Cell(r, c)] = Offset(
                        padding + (c + 0.5f) * cell,
                        padding + (r + 0.5f) * cell,
                    )
                }
            }
            return BoardLayout(
                isHex = false,
                centers = centers,
                size = Size(cols * cell + 2 * padding, rows * cell + 2 * padding),
                textSize = cell * 0.6f,
                radius = cell / 2f,
                cellW = cell,
                cellH = cell,
                padding = padding,
            )
        }
    }
}
