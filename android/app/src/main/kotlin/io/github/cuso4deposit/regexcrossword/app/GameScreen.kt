package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.cuso4deposit.regexcrossword.engine.Cell
import io.github.cuso4deposit.regexcrossword.engine.Judge
import io.github.cuso4deposit.regexcrossword.engine.JudgeResult
import io.github.cuso4deposit.regexcrossword.engine.Puzzle
import io.github.cuso4deposit.regexcrossword.engine.Solver
import io.github.cuso4deposit.regexcrossword.engine.SolverLimitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Reading-direction arrow; hex X reads bottom-to-top, rect X top-to-bottom. */
private fun directionSymbol(kind: String, family: String): String = when (family) {
    "y" -> "\u2192"
    "x" -> if (kind == "rect") "\u2193" else "\u2191"
    else -> "\u2193"
}

@Composable
fun GameScreen(id: PuzzleId, onBack: () -> Unit, onNextLevel: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GameStore(context) }
    val puzzleStore = remember { PuzzleStore(context) }
    val level = levelOf(id.difficulty, id.seed).coerceAtLeast(0)
    val key = "${id.version}/${id.difficulty.name}/${id.seed}"

    val preloaded = remember(key) { puzzleStore.loadOrNull(id) }
    var puzzle by remember(key) { mutableStateOf(preloaded) }
    LaunchedEffect(key) {
        if (puzzle == null) {
            val generated = withContext(Dispatchers.Default) { generatePuzzle(id) }
            puzzleStore.savePuzzle(id.seed, generated)
            puzzle = generated
        }
    }

    // Keep a buffer of ~50 unsolved unique puzzles ahead while playing HARD.
    LaunchedEffect(key) {
        if (!id.difficulty.unique) return@LaunchedEffect
        var unsolved = 0
        var offset = 0
        while (unsolved < 50 && offset < 300) {
            val nextLevel = level + offset
            val nextId = PuzzleId(id.version, id.difficulty, seedFor(id.difficulty, nextLevel))
            if (!store.isSolved(nextId)) {
                if (!puzzleStore.has(nextId.seed)) {
                    val generated = withContext(Dispatchers.Default) { generatePuzzle(nextId) }
                    puzzleStore.savePuzzle(nextId.seed, generated)
                }
                unsolved++
            }
            offset++
        }
    }

    val loaded = puzzle
    if (loaded == null) {
        GeneratingScreen(id, onBack)
        return
    }
    GameContent(id, loaded, onBack, onNextLevel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratingScreen(id: PuzzleId, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${id.difficulty.label} \u00B7 Level ${levelOf(id.difficulty, id.seed) + 1}") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Generating a unique puzzle\u2026", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "HARD levels are solved to guarantee a unique answer; this can take a few seconds.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GameContent(
    id: PuzzleId,
    puzzle: Puzzle,
    onBack: () -> Unit,
    onNextLevel: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { GameStore(context) }
    val level = levelOf(id.difficulty, id.seed).coerceAtLeast(0)
    val key = "${id.version}/${id.difficulty.name}/${id.seed}"
    val solver = remember(puzzle) { Solver(puzzle) }
    val saved = remember(key) { store.load(id) }
    val validCells = remember(puzzle) { puzzle.geometry.cells().toHashSet() }
    val grid = remember(key) {
        mutableStateMapOf<Cell, Char>().apply {
            putAll(saved.grid.filterKeys { it in validCells })
        }
    }
    val notes = remember(key) {
        mutableStateMapOf<Cell, Set<Char>>().apply {
            putAll(saved.notes.filterKeys { it in validCells })
        }
    }
    var selected by remember(key) {
        mutableStateOf(saved.selected?.takeIf { it in validCells })
    }
    var notesMode by remember(key) { mutableStateOf(false) }
    var message by remember(key) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmSolve by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var solvedNow by remember(key) { mutableStateOf(store.isSolved(id)) }
    var solvedNotified by remember(key) { mutableStateOf(store.isSolved(id)) }
    var elapsed by remember(key) { mutableStateOf(store.elapsedSeconds(id)) }
    var timerRunning by remember(key) { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }

    data class Snapshot(
        val grid: Map<Cell, Char>,
        val notes: Map<Cell, Set<Char>>,
        val selected: Cell?,
    )

    val undoStack = remember(key) { mutableStateListOf<Snapshot>() }
    val redoStack = remember(key) { mutableStateListOf<Snapshot>() }

    fun currentSnapshot() = Snapshot(grid.toMap(), notes.toMap(), selected)

    fun pushUndo() {
        undoStack.add(currentSnapshot())
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun restore(snapshot: Snapshot) {
        grid.clear()
        grid.putAll(snapshot.grid)
        notes.clear()
        notes.putAll(snapshot.notes)
        selected = snapshot.selected
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.add(currentSnapshot())
        restore(previous)
        message = null
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(currentSnapshot())
        restore(next)
        message = null
    }

    LaunchedEffect(id, store) { store.savePosition(id) }
    LaunchedEffect(id, store) {
        snapshotFlow { Triple(grid.toMap(), notes.toMap(), selected) }.collect { (g, n, s) ->
            withContext(Dispatchers.IO) { store.save(id, g, n, s) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        store.save(id, grid.toMap(), notes.toMap(), selected)
    }

    LaunchedEffect(key, timerRunning, solvedNow) {
        while (timerRunning && !solvedNow) {
            delay(1000)
            elapsed += 1
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { timerRunning = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        timerRunning = false
        store.saveElapsedSeconds(id, elapsed)
    }
    DisposableEffect(key) {
        onDispose { store.saveElapsedSeconds(id, elapsed) }
    }

    val result: JudgeResult by remember(puzzle) {
        derivedStateOf { Judge.judge(puzzle, grid) }
    }
    LaunchedEffect(result.solved) {
        if (result.solved) {
            store.markSolved(id, grid.toMap())
            solvedNow = true
            if (!solvedNotified) {
                solvedNotified = true
                val outcome = snackbarHostState.showSnackbar(
                    message = "Solved! Every line matches.",
                    actionLabel = "Next level",
                    duration = SnackbarDuration.Long,
                )
                if (outcome == SnackbarResult.ActionPerformed) onNextLevel()
            }
        }
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
        pushUndo()
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
        pushUndo()
        grid.clear()
        grid.putAll(completion)
        notes.clear()
        selected = null
        message = "Filled one valid solution."
    }

    fun fillGivens() {
        val fills = solver.givenLetters().filter { (cell, ch) -> grid[cell] != ch }
        if (fills.isEmpty()) {
            message = "No directly-given letters to fill."
            return
        }
        pushUndo()
        for ((cell, ch) in fills) {
            grid[cell] = ch
            notes.remove(cell)
        }
        message = "Filled ${fills.size} given letter(s)."
    }

    fun loadSavedSolution() {
        val stored = store.solvedGrid(id)
        if (stored == null) {
            message = "No saved solution for this level."
            return
        }
        pushUndo()
        grid.clear()
        grid.putAll(stored.filterKeys { it in validCells })
        notes.clear()
        selected = null
        message = "Loaded the saved solution."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${id.difficulty.label} \u00B7 Level ${level + 1}")
                        Text(
                            formatElapsed(elapsed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Tip("Back to levels") {
                        TextButton(onClick = onBack) {
                            Text("\u2190", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    Tip("Undo") {
                        TextButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                            Text("\u21B6", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Tip("Redo") {
                        TextButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) {
                            Text("\u21B7", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Tip("Menu") {
                        Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text("\u22EE", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Solve (fill a solution)") },
                                    onClick = {
                                        menuOpen = false
                                        confirmSolve = true
                                    },
                                )
                                if (solvedNow) {
                                    DropdownMenuItem(
                                        text = { Text("Load saved solution") },
                                        onClick = {
                                            menuOpen = false
                                            loadSavedSolution()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(8.dp),
        ) {
            // Fixed-height clue area so selecting a cell never resizes the board.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (puzzle.geometry.kind == "hex") 176.dp else 128.dp),
            ) {
                CluePanel(puzzle, selected, result)
            }
            BoardView(
                puzzle = puzzle,
                grid = grid,
                notes = notes,
                selected = selected,
                result = result,
                onCellTap = { selected = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            StatusLine(result, message)
            LetterPalette(
                alphabet = alphabet,
                onLetter = { letter ->
                    selected?.let { cell ->
                        pushUndo()
                        if (notesMode) {
                            val current = notes[cell] ?: emptySet()
                            val updated = if (letter in current) current - letter else current + letter
                            if (updated.isEmpty()) notes.remove(cell) else notes[cell] = updated
                        } else {
                            grid[cell] = letter
                            notes.remove(cell)
                        }
                        message = null
                    }
                },
                onClear = {
                    selected?.let { cell ->
                        pushUndo()
                        if (notesMode) {
                            notes.remove(cell)
                        } else if (grid.remove(cell) == null) {
                            notes.remove(cell)
                        }
                        message = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tip("Reveal one cell that fits what you have") {
                    OutlinedButton(onClick = { hint() }) { Text("Hint") }
                }
                Tip("Fill cells whose letter a clue writes down") {
                    OutlinedButton(onClick = { fillGivens() }) { Text("Givens") }
                }
                if (notesMode) {
                    Tip("Pencil marks on \u2014 tap to turn off") {
                        Button(onClick = { notesMode = false }) { Text("Notes on") }
                    }
                } else {
                    Tip("Pencil marks \u2014 tap letters to add or remove candidates") {
                        OutlinedButton(onClick = { notesMode = true }) { Text("Notes") }
                    }
                }
                Tip("Erase every letter and note") {
                    OutlinedButton(
                        onClick = { if (grid.isNotEmpty() || notes.isNotEmpty()) confirmClear = true },
                    ) { Text("Clear") }
                }
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear?") },
            text = { Text("Erase every letter and note on this level? You can undo this.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        pushUndo()
                        grid.clear()
                        notes.clear()
                        selected = null
                        message = null
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

}

@Composable
private fun CluePanel(puzzle: Puzzle, selected: Cell?, result: JudgeResult) {
    val kind = puzzle.geometry.kind
    val isHex = kind == "hex"
    if (selected == null) {
        Text(
            text = if (isHex) {
                "Tap a cell to see its three clues and their reading directions."
            } else {
                "Tap a cell to see its row and column clues."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val geometry = puzzle.geometry
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
            val markColor = when {
                lineResult == null -> MaterialTheme.colorScheme.onSurfaceVariant
                lineResult.ok -> FamilyColors.label(line.family)
                lineResult.complete -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(modifier = Modifier.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.Top) {
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
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = directionSymbol(kind, line.family),
                        color = FamilyColors.label(line.family),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = clue,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (mark.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = mark,
                            color = markColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val word = lineResult?.word
                if (!word.isNullOrEmpty()) {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = FamilyColors.label(line.family),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 32.dp, top = 1.dp),
                    )
                }
            }
        }
        Text(
            text = if (isHex) {
                "Ringed cell = start of the line (X reads bottom\u2192top)."
            } else {
                "Each row reads left to right; each column reads top to bottom."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(result: JudgeResult, message: String?) {
    val unfinished = result.lines.count { !it.complete }
    val text = when {
        message != null -> message
        result.solved -> "Solved! Every line matches."
        result.complete -> "${result.failures.size} line(s) do not match."
        unfinished > 0 -> "$unfinished line(s) still to fill."
        else -> "Fill all cells."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
