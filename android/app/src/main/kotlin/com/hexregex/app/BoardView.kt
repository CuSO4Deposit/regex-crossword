package com.hexregex.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.hexregex.engine.Cell
import com.hexregex.engine.JudgeResult
import com.hexregex.engine.Puzzle

/**
 * Draws the board and reports taps. Fill precedence is
 * selected > wrong line > active line > idle.
 */
@Composable
fun BoardView(
    puzzle: Puzzle,
    grid: Map<Cell, Char>,
    selected: Cell?,
    result: JudgeResult,
    showErrors: Boolean,
    onCellTap: (Cell) -> Unit,
    modifier: Modifier = Modifier,
) {
    val geometry = puzzle.geometry
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    val activeCells = remember(selected, geometry) {
        selected?.let { geometry.linesForCell(it).flatMap { line -> line.cells }.toSet() }
            ?: emptySet()
    }
    val wrongCells: Set<Cell> =
        if (showErrors) {
            result.failures
                .flatMap { failure -> geometry.line(failure.family, failure.index).cells }
                .toSet()
        } else {
            emptySet()
        }

    val colors = BoardColors(
        idle = scheme.surfaceVariant,
        active = scheme.secondaryContainer,
        selected = scheme.primary,
        wrong = scheme.errorContainer,
        border = scheme.outline,
        letter = scheme.onSurface,
        selectedLetter = scheme.onPrimary,
        wrongLetter = scheme.onErrorContainer,
    )

    BoxWithConstraints(modifier) {
        val paddingPx = with(density) { 8.dp.toPx() }
        val available = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val layout = remember(geometry, available, paddingPx) {
            BoardLayout.of(geometry, available, paddingPx)
        }
        val baseStyle = remember(layout, density) {
            TextStyle(
                fontSize = with(density) { layout.textSize.toSp() },
                fontWeight = FontWeight.Bold,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(layout) {
                    detectTapGestures { offset ->
                        layout.hitTest(offset)?.let(onCellTap)
                    }
                },
        ) {
            for (cell in geometry.cells()) {
                val fill = when {
                    cell == selected -> colors.selected
                    cell in wrongCells -> colors.wrong
                    cell in activeCells -> colors.active
                    else -> colors.idle
                }
                val path = Path()
                layout.polygon(cell).forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                drawPath(path, color = fill, style = Fill)
                drawPath(path, color = colors.border, style = Stroke(width = 1.dp.toPx()))

                val letter = grid[cell]
                if (letter != null) {
                    val color = when {
                        cell == selected -> colors.selectedLetter
                        cell in wrongCells -> colors.wrongLetter
                        else -> colors.letter
                    }
                    val measured = measurer.measure(
                        AnnotatedString(letter.toString()),
                        baseStyle.copy(color = color),
                    )
                    val center = layout.centers.getValue(cell)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            center.x - measured.size.width / 2f,
                            center.y - measured.size.height / 2f,
                        ),
                    )
                }
            }
        }
    }
}

private data class BoardColors(
    val idle: Color,
    val active: Color,
    val selected: Color,
    val wrong: Color,
    val border: Color,
    val letter: Color,
    val selectedLetter: Color,
    val wrongLetter: Color,
)
