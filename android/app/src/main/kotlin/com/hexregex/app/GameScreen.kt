package com.hexregex.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hexregex.engine.Cell
import com.hexregex.engine.GenConfig
import com.hexregex.engine.Generator
import com.hexregex.engine.Judge
import com.hexregex.engine.JudgeResult
import com.hexregex.engine.Puzzle
import com.hexregex.engine.Solver
import com.hexregex.engine.SolverLimitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Pinned generator presets: edge 5, full alphabet, constructive mode. */
enum class Difficulty(val label: String, val tier: String) {
    EASY("Easy", "easy"),
    MEDIUM("Medium", "medium"),
    HARD("Hard", "hard"),
}

/** `level_id -> seed`; frozen forever so a level id always means one puzzle. */
const val SEED_BASE = 1000

private fun generatePuzzle(difficulty: Difficulty, level: Int): Puzzle =
    Generator.constructive(
        GenConfig(edge = 5, difficulty = difficulty.tier, seed = SEED_BASE + level),
    )

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GameScreen() {
    val context = LocalContext.current
    val store = remember { GameStore(context) }
    var difficulty by remember { mutableStateOf(store.loadDifficulty(Difficulty.MEDIUM)) }
    var level by remember { mutableStateOf(store.loadLevel()) }

    val cache = remember { HashMap<String, Puzzle>() }
    val cacheOrder = remember { ArrayDeque<String>() }
    fun cachePut(key: String, value: Puzzle) {
        if (cache.put(key, value) == null) cacheOrder.addLast(key)
        while (cacheOrder.size > 64) cache.remove(cacheOrder.removeFirst())
    }

    val puzzle = remember(difficulty, level) {
        val key = "${difficulty.name}/$level"
        cache[key] ?: generatePuzzle(difficulty, level).also { cachePut(key, it) }
    }
    val solver = remember(puzzle) { Solver(puzzle) }
    val saved = remember(difficulty, level) { store.load(difficulty, level) }
    val validCells = remember(puzzle) { puzzle.geometry.cells().toHashSet() }
    val grid = remember(difficulty, level) {
        mutableStateMapOf<Cell, Char>().apply {
            putAll(saved.grid.filterKeys { it in validCells })
        }
    }
    val notes = remember(difficulty, level) {
        mutableStateMapOf<Cell, Set<Char>>().apply {
            putAll(saved.notes.filterKeys { it in validCells })
        }
    }
    var selected by remember(difficulty, level) {
        mutableStateOf(saved.selected?.takeIf { it in validCells })
    }
    var notesMode by remember(difficulty, level) { mutableStateOf(false) }
    var showErrors by remember(difficulty, level) { mutableStateOf(false) }
    var message by remember(difficulty, level) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmSolve by remember { mutableStateOf(false) }

    // Prefetch the next few levels off the main thread.
    LaunchedEffect(difficulty, level) {
        for (offset in 1..3) {
            val key = "${difficulty.name}/${level + offset}"
            if (!cache.containsKey(key)) {
                val generated = withContext(Dispatchers.Default) {
                    generatePuzzle(difficulty, level + offset)
                }
                cachePut(key, generated)
            }
        }
    }
    LaunchedEffect(difficulty, level, store) { store.savePosition(difficulty, level) }
    LaunchedEffect(difficulty, level, store) {
        snapshotFlow { Triple(grid.toMap(), notes.toMap(), selected) }.collect { (g, n, s) ->
            withContext(Dispatchers.IO) { store.save(difficulty, level, g, n, s) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        store.save(difficulty, level, grid.toMap(), notes.toMap(), selected)
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
                TopAppBar(
                    title = { Text("Regex Crossword") },
                    actions = {
                        Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text("\u22EE", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Solve (fill a solution)") },
                                    onClick = {
                                        menuOpen = false
                                        confirmSolve = true
                                    },
                                )
                            }
                        }
                    },
                )
                TabRow(selectedTabIndex = difficulty.ordinal) {
                    for (option in Difficulty.entries) {
                        Tab(
                            selected = option == difficulty,
                            onClick = { difficulty = option },
                            text = { Text(option.label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { if (level > 0) level-- }, enabled = level > 0) {
                        Text("\u25C0")
                    }
                    Text(
                        text = "Level ${level + 1}",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { level++ }) { Text("\u25B6") }
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
            }
        }
    }

    if (confirmSolve) {
        AlertDialog(
            onDismissRequest = { confirmSolve = false },
            title = { Text("Solve?") },
            text = { Text("Fill one complete valid solution? This overwrites your current entries.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSolve = false
                        solveAll()
                    },
                ) { Text("Solve") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSolve = false }) { Text("Cancel") }
            },
        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(FamilyColors.label(line.family)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = line.family.uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.width(8.dp))
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
