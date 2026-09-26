package com.hexregex.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeometryTest {
    @Test
    fun `hexagon edge 5 has the expected shape`() {
        val geo = HexGeometry(5)
        assertEquals(9, geo.size)
        assertEquals(4, geo.mid)
        assertEquals(61, geo.cellCount())
        assertEquals(27, geo.lines.size) // 3 * (2n - 1)
        assertEquals(5, geo.rowSize(0))
        assertEquals(9, geo.rowSize(4))
        assertEquals(5, geo.rowSize(8))
        geo.validate()
    }

    @Test
    fun `hexagon line directions match the Python doc`() {
        val geo = HexGeometry(5)

        // Y line 0 is the whole row, left to right.
        assertEquals(
            (0..4).map { Cell(0, it) },
            geo.yLine(0).cells,
        )

        // X line 0 is read bottom to top.
        assertEquals(
            listOf(Cell(4, 0), Cell(3, 0), Cell(2, 0), Cell(1, 0), Cell(0, 0)),
            geo.xLine(0).cells,
        )

        // Z line 0 is read top to bottom.
        assertEquals(
            listOf(Cell(4, 0), Cell(5, 0), Cell(6, 0), Cell(7, 0), Cell(8, 0)),
            geo.zLine(0).cells,
        )
    }

    @Test
    fun `every hexagon cell lies on exactly one line per family`() {
        val geo = HexGeometry(5)
        for (cell in geo.cells()) {
            val (x, y, z) = geo.lineIndices(cell)
            assertTrue(geo.xLine(x).cells.contains(cell), "x for $cell")
            assertTrue(geo.yLine(y).cells.contains(cell), "y for $cell")
            assertTrue(geo.zLine(z).cells.contains(cell), "z for $cell")
        }
    }

    @Test
    fun `rectangle directions are rows left-to-right and columns top-to-bottom`() {
        val geo = RectGeometry(5, 5)
        assertEquals(10, geo.lines.size)
        assertEquals((0..4).map { Cell(0, it) }, geo.line("y", 0).cells)
        assertEquals((0..4).map { Cell(it, 0) }, geo.line("x", 0).cells)
        geo.validate()
    }

    @Test
    fun `hexagon edges other than 5 still validate`() {
        for (edge in 1..7) {
            val geo = HexGeometry(edge)
            geo.validate()
            assertEquals(3 * edge * (edge - 1) + 1, geo.cellCount())
        }
    }

    @Test
    fun `rect and hex kinds differ`() {
        assertNotEquals(HexGeometry(5).kind, RectGeometry(5, 5).kind)
    }
}
