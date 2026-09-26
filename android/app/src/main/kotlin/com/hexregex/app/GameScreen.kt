package com.hexregex.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hexregex.engine.Cell
import com.hexregex.engine.Judge
import com.hexregex.engine.JudgeResult
import com.hexregex.engine.Puzzle

/** Built-in fixtures, one per pinned difficulty preset (seed 1000). */
enum class Difficulty(val label: String, val asset: String) {
    EASY("Easy", "rect_easy_1000.json"),
    MEDIUM("Medium", "hex_medium_1000.json"),
    HARD("Hard", "hex_hard_1000.json"),
}

private fun loadPuzzle(context: Context, difficulty: Difficulty): Puzzle =
    context.assets.open(difficulty.asset).bufferedReader().use { Puzzle.fromJson(it.readText()) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen() {
    val context = LocalContext.current
    var difficulty by remember { mutableStateOf(Difficulty.MEDIUM) }
    val puzzle = remember(difficulty) { loadPuzzle(context, difficulty) }
    val grid = remember(difficulty) { mutableStateMapOf<Cell, Char>() }
    var selected by remember(difficulty) { mutableStateOf<Cell?>(null) }
    var showErrors by remember(difficulty) { mutableStateOf(false) }

    val result: JudgeResult by remember(puzzle) {
        derivedStateOf { Judge.judge(puzzle, grid) }
    }
    val alphabet = remember { ('A'..'Z').toList() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Regex Crossword") })
                TabRow(selectedTabIndex = difficulty.ordinal) {
                    for (option in Difficulty.entries) {
                        Tab(
                            selected = option == difficulty,
                            onClick = { difficulty = option },
                            text = { Text(option.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(8.dp),
        ) {
            CluePanel(puzzle, selected, result)
            BoardView(
                puzzle = puzzle,
                grid = grid,
                selected = selected,
                result = result,
                showErrors = showErrors || result.complete,
                onCellTap = { selected = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            StatusLine(result, showErrors)
            LetterPalette(
                alphabet = alphabet,
                onLetter = { letter ->
                    selected?.let {
                        grid[it] = letter
                        showErrors = false
                    }
                },
                onClear = {
                    selected?.let {
                        grid.remove(it)
                        showErrors = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { showErrors = true }) { Text("Check") }
                OutlinedButton(
                    onClick = {
                        grid.clear()
                        selected = null
                        showErrors = false
                    },
                ) { Text("Clear") }
                TextButton(
                    onClick = {
                        grid.clear()
                        grid.putAll(puzzle.storedSolutionGrid())
                        selected = null
                        showErrors = false
                    },
                ) { Text("Fill solution") }
            }
        }
    }
}

@Composable
private fun CluePanel(puzzle: Puzzle, selected: Cell?, result: JudgeResult) {
    if (selected == null) {
        Text(
            text = "Tap a cell to see the three clues through it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val geometry = puzzle.geometry
    Column {
        for (line in geometry.linesForCell(selected)) {
            val clue = puzzle.clue(line.family, line.index)
            val lineResult = result.lines.firstOrNull {
                it.family == line.family && it.index == line.index
            }
            val mark = when {
                lineResult == null -> ""
                !lineResult.complete -> "\u2022"
                lineResult.ok -> "\u2713"
                else -> "\u2717"
            }
            Row {
                Text(
                    text = "${line.family.uppercase()}: ",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = clue,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "  $mark ${lineResult?.word ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(result: JudgeResult, showErrors: Boolean) {
    val text = when {
        result.solved -> "Solved! Every line matches."
        showErrors && result.complete -> "${result.failures.size} line(s) do not match."
        showErrors -> "Some cells are still empty."
        else -> "Fill all cells, then press Check."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
