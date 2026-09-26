package com.hexregex.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hexregex.engine.Cell
import com.hexregex.engine.Judge
import com.hexregex.engine.JudgeResult
import com.hexregex.engine.Puzzle
import com.hexregex.engine.Solver
import com.hexregex.engine.SolverLimitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Built-in fixtures, one per pinned difficulty preset (seed 1000). */
enum class Difficulty(val label: String, val asset: String) {
    EASY("Easy", "rect_easy_1000.json"),
    MEDIUM("Medium", "hex_medium_1000.json"),
    HARD("Hard", "hex_hard_1000.json"),
}

private fun loadPuzzle(context: Context, difficulty: Difficulty): Puzzle =
    context.assets.open(difficulty.asset).bufferedReader().use { Puzzle.fromJson(it.readText()) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GameScreen() {
    val context = LocalContext.current
    val store = remember { GameStore(context) }
    var difficulty by remember { mutableStateOf(store.loadDifficulty(Difficulty.MEDIUM)) }
    val puzzle = remember(difficulty) { loadPuzzle(context, difficulty) }
    val solver = remember(puzzle) { Solver(puzzle) }
    val saved = remember(difficulty) { store.load(difficulty) }
    val validCells = remember(puzzle) { puzzle.geometry.cells().toHashSet() }
    val grid = remember(difficulty) {
        mutableStateMapOf<Cell, Char>().apply {
            putAll(saved.grid.filterKeys { it in validCells })
        }
    }
    val notes = remember(difficulty) {
        mutableStateMapOf<Cell, Set<Char>>().apply {
            putAll(saved.notes.filterKeys { it in validCells })
        }
    }
    var selected by remember(difficulty) {
        mutableStateOf(saved.selected?.takeIf { it in validCells })
    }
    var notesMode by remember(difficulty) { mutableStateOf(false) }
    var showErrors by remember(difficulty) { mutableStateOf(false) }
    var message by remember(difficulty) { mutableStateOf<String?>(null) }

    LaunchedEffect(difficulty, store) { store.saveDifficulty(difficulty) }
    // Persist on every change (not only on exit), off the main thread.
    LaunchedEffect(difficulty, store) {
        snapshotFlow { Triple(grid.toMap(), notes.toMap(), selected) }.collect { (g, n, s) ->
            withContext(Dispatchers.IO) { store.save(difficulty, g, n, s) }
        }
    }
    // Belt-and-braces: also flush when the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        store.save(difficulty, grid.toMap(), notes.toMap(), selected)
    }

    val result: JudgeResult by remember(puzzle) {
        derivedStateOf { Judge.judge(puzzle, grid) }
    }
    val alphabet = remember { ('A'..'Z').toList() }

    fun firstEmpty(): Cell? =
        selected?.takeIf { grid[it] == null }
            ?: puzzle.geometry.cells().firstOrNull { grid[it] == null }

    fun hint() {
        val target = firstEmpty()
        if (target == null) {
            message = "Every cell is filled."
            return
        }
        val completion = try {
            solver.solveWith(grid.toMap())
        } catch (_: SolverLimitException) {
            message = "Solver gave up on this clue set."
            return
        }
        if (completion == null) {
            message = "Your entries are inconsistent \u2014 no completion exists."
            return
        }
        grid[target] = completion.getValue(target)
        notes.remove(target)
        selected = target
        message = "Hint: revealed one cell consistent with your grid."
    }

    fun solveAll() {
        val completion = try {
            solver.solveWith(grid.toMap())
        } catch (_: SolverLimitException) {
            message = "Solver gave up on this clue set."
            return
        }
        if (completion == null) {
            message = "Your entries are inconsistent \u2014 clear or fix them first."
            return
        }
        grid.clear()
        grid.putAll(completion)
        notes.clear()
        selected = null
        message = "Filled one valid solution."
    }

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
                notes = notes,
                selected = selected,
                result = result,
                showErrors = showErrors || result.complete,
                onCellTap = { selected = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            StatusLine(result, showErrors, message)
            LetterPalette(
                alphabet = alphabet,
                onLetter = { letter ->
                    selected?.let { cell ->
                        if (notesMode) {
                            val current = notes[cell] ?: emptySet()
                            val updated = if (letter in current) current - letter else current + letter
                            if (updated.isEmpty()) notes.remove(cell) else notes[cell] = updated
                        } else {
                            grid[cell] = letter
                            notes.remove(cell)
                        }
                        showErrors = false
                        message = null
                    }
                },
                onClear = {
                    selected?.let { cell ->
                        if (notesMode) {
                            notes.remove(cell)
                        } else if (grid.remove(cell) == null) {
                            notes.remove(cell)
                        }
                        showErrors = false
                        message = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { showErrors = true }) { Text("Check") }
                OutlinedButton(onClick = { hint() }) { Text("Hint") }
                if (notesMode) {
                    Button(onClick = { notesMode = false }) { Text("Notes on") }
                } else {
                    OutlinedButton(onClick = { notesMode = true }) { Text("Notes") }
                }
                OutlinedButton(
                    onClick = {
                        grid.clear()
                        notes.clear()
                        selected = null
                        showErrors = false
                        message = null
                    },
                ) { Text("Clear") }
                OutlinedButton(onClick = { solveAll() }) { Text("Solve") }
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
private fun StatusLine(result: JudgeResult, showErrors: Boolean, message: String?) {
    val text = when {
        message != null -> message
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
