package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.cuso4deposit.regexcrossword.engine.Cell
import io.github.cuso4deposit.regexcrossword.engine.Geometry
import io.github.cuso4deposit.regexcrossword.engine.HexGeometry
import io.github.cuso4deposit.regexcrossword.engine.RectGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardLayoutTest {
    private val padding = 8f

    /**
     * The invariant that was broken: a tap on the drawn centre of a cell must
     * hit that same cell, whatever the shape of the available box. Off-centre
     * boxes used to be shifted by the centring origin (see BoardLayout.rect).
     */
    private fun assertEveryCellHitsItself(geometry: Geometry, available: Size) {
        val layout = BoardLayout.of(geometry, available, padding)
        for (cell in geometry.cells()) {
            val center = layout.centers.getValue(cell)
            assertEquals(
                "tap on centre of $cell in ${available.width}x${available.height} box",
                cell,
                layout.hitTest(center),
            )
        }
    }

    @Test
    fun rectHitsEveryCellInWideShortBox() {
        assertEveryCellHitsItself(RectGeometry(5, 5), Size(1080f, 616f))
    }

    @Test
    fun rectHitsEveryCellInTallNarrowBox() {
        assertEveryCellHitsItself(RectGeometry(5, 5), Size(1080f, 1620f))
    }

    @Test
    fun rectHitsEveryCellInSquareBox() {
        assertEveryCellHitsItself(RectGeometry(5, 5), Size(800f, 800f))
    }

    @Test
    fun rectMissesOutsideTheBoard() {
        val layout = BoardLayout.of(RectGeometry(5, 5), Size(1080f, 616f), padding)
        val first = layout.centers.getValue(Cell(0, 0))
        assertNull("left of the board must miss", layout.hitTest(Offset(first.x - 200f, first.y)))
        assertNull("below the board must miss", layout.hitTest(Offset(first.x, first.y + 5000f)))
    }

    @Test
    fun hexHitsEveryCell() {
        assertEveryCellHitsItself(HexGeometry(5), Size(1080f, 1600f))
        assertEveryCellHitsItself(HexGeometry(5), Size(1080f, 600f))
    }
}
