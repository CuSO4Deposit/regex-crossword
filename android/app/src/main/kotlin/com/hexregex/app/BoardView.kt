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
    notes: Map<Cell, Set<Char>>,
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

    val activeByFamily = remember(selected, geometry) {
        selected?.let { cell ->
            geometry.linesForCell(cell).associate { line -> line.family to line.cells.toSet() }
        } ?: emptyMap()
    }
    val emptyCells = remember { emptySet<Cell>() }
    val wrongCells: Set<Cell> =
        if (showErrors) {
            result.failures
                .filter { it.complete }
                .flatMap { failure -> geometry.line(failure.family, failure.index).cells }
                .toSet()
        } else {
            emptySet()
        }

    val colors = BoardColors(
        idle = scheme.surfaceVariant,
        xTint = FamilyColors.tint("x"),
        yTint = FamilyColors.tint("y"),
        zTint = FamilyColors.tint("z"),
        selected = scheme.primary,
        wrong = scheme.error,
        border = scheme.outline,
        letter = scheme.onSurface,
        selectedLetter = scheme.onPrimary,
        wrongLetter = scheme.error,
        note = scheme.onSurfaceVariant,
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
                    cell in (activeByFamily["x"] ?: emptyCells) -> colors.xTint
                    cell in (activeByFamily["y"] ?: emptyCells) -> colors.yTint
                    cell in (activeByFamily["z"] ?: emptyCells) -> colors.zTint
                    else -> colors.idle
                }
                val path = Path()
                layout.polygon(cell).forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                drawPath(path, color = fill, style = Fill)
                drawPath(path, color = colors.border, style = Stroke(width = 1.dp.toPx()))
                if (cell in wrongCells) {
                    drawPath(path, color = colors.wrong, style = Stroke(width = 3.dp.toPx()))
                }

                val letter = grid[cell]
                val center = layout.centers.getValue(cell)
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
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            center.x - measured.size.width / 2f,
                            center.y - measured.size.height / 2f,
                        ),
                    )
                } else {
                    val candidates = notes[cell]
                    if (!candidates.isNullOrEmpty()) {
                        val sorted = candidates.sorted()
                        val cols = if (sorted.size > 12) 5 else 4
                        val rows = (sorted.size + cols - 1) / cols
                        val fontPx = minOf(
                            layout.textSize * 0.50f,
                            layout.textSize * 1.7f / maxOf(cols, rows),
                        )
                        val noteStyle = baseStyle.copy(
                            color = if (cell == selected) colors.selectedLetter else colors.note,
                            fontSize = with(density) { fontPx.toSp() },
                            fontWeight = FontWeight.Normal,
                        )
                        for (index in sorted.indices) {
                            val row = index / cols
                            val col = index % cols
                            val inRow = minOf(cols, sorted.size - row * cols)
                            val dx = (col - (inRow - 1) / 2f) * fontPx * 1.05f
                            val dy = (row - (rows - 1) / 2f) * fontPx * 1.15f
                            val measured = measurer.measure(
                                AnnotatedString(sorted[index].toString()),
                                noteStyle,
                            )
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(
                                    center.x + dx - measured.size.width / 2f,
                                    center.y + dy - measured.size.height / 2f,
                                ),
                            )
                        }
                    }
                }
            }

            // Ring the reading-start cell of each active line.
            if (selected != null) {
                for (line in geometry.linesForCell(selected)) {
                    val start = line.cells.firstOrNull() ?: continue
                    val marker = Path()
                    layout.polygon(start).forEachIndexed { index, point ->
                        if (index == 0) marker.moveTo(point.x, point.y) else marker.lineTo(point.x, point.y)
                    }
                    marker.close()
                    drawPath(
                        marker,
                        color = FamilyColors.label(line.family),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
    }
}

private data class BoardColors(
    val idle: Color,
    val xTint: Color,
    val yTint: Color,
    val zTint: Color,
    val selected: Color,
    val wrong: Color,
    val border: Color,
    val letter: Color,
    val selectedLetter: Color,
    val wrongLetter: Color,
    val note: Color,
)
