package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

private enum class Slot { Clue, Board, Status, Controls, ControlsScroll }

/**
 * Stack the clue panel, board and controls while protecting the board.
 *
 * A plain `Column` with a `weight(1f)` board lets every other child eat the
 * board's space: at large system font scales the letter palette and action
 * rows grew until the board had none left. This measures the controls at
 * their natural size first, gives the board at least [minBoardHeight] while
 * the fixed [clueHeight] slice yields first, and only when even that does not
 * fit does it shrink the clue and let the controls scroll internally, so the
 * board and the keyboard both stay reachable.
 *
 * [minBoardHeight] is a hard floor: the board is never given less while the
 * viewport can also hold [minControlsHeight] of keyboard. Below that the
 * board and the keyboard split what is left (board first); neither collapses.
 */
@Composable
fun AdaptiveGameLayout(
    clueHeight: Dp,
    minBoardHeight: Dp,
    minControlsHeight: Dp,
    clue: @Composable () -> Unit,
    board: @Composable (Modifier) -> Unit,
    status: @Composable () -> Unit,
    palette: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = Constraints(
            minWidth = 0,
            minHeight = 0,
            maxWidth = width,
            maxHeight = Constraints.Infinity,
        )

        val statusP = subcompose(Slot.Status, status).map { it.measure(loose) }
        val statusH = statusP.sumOf { it.height }

        val controlsNaturalP = subcompose(Slot.Controls) {
            Column(Modifier.fillMaxWidth()) {
                palette()
                actions()
            }
        }.map { it.measure(loose) }
        val controlsNaturalH = controlsNaturalP.sumOf { it.height }

        val available = (height - statusH).coerceAtLeast(0)
        val boardMin = minBoardHeight.roundToPx()
        val clueNatural = clueHeight.roundToPx()
        val fits = controlsNaturalH + clueNatural + boardMin <= available

        val clueH: Int
        val boardH: Int
        val controlsH: Int
        if (fits) {
            clueH = clueNatural
            boardH = available - clueH - controlsNaturalH
            controlsH = controlsNaturalH
        } else {
            // Board and keyboard both stay usable; the clue gives way and the
            // controls scroll if they cannot fit at their natural height.
            val controlsFloor = minControlsHeight.roundToPx()
            if (available >= boardMin + controlsFloor) {
                boardH = boardMin
                clueH = minOf(clueNatural, (available - boardH - controlsFloor).coerceAtLeast(0))
                controlsH = available - boardH - clueH
            } else {
                // Not even the floor and a minimum keyboard fit: split the
                // space, board first, so neither reaches zero.
                boardH = available * 2 / 3
                clueH = 0
                controlsH = available - boardH
            }
        }

        val clueP = subcompose(Slot.Clue, clue).map { it.measure(Constraints.fixed(width, clueH)) }
        val boardP = subcompose(Slot.Board) { board(Modifier) }
            .map { it.measure(Constraints.fixed(width, boardH)) }
        val controlsP = if (fits) {
            controlsNaturalP
        } else {
            subcompose(Slot.ControlsScroll) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    palette()
                    actions()
                }
            }.map { it.measure(Constraints.fixed(width, controlsH)) }
        }

        layout(width, height) {
            var y = 0
            clueP.forEach { it.place(0, y); y += it.height }
            boardP.forEach { it.place(0, y); y += it.height }
            statusP.forEach { it.place(0, y); y += it.height }
            controlsP.forEach { it.place(0, y); y += it.height }
        }
    }
}
