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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch
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

    var puzzle by remember(key) { mutableStateOf<Puzzle?>(null) }
    var loadError by remember(key) { mutableStateOf(false) }
    var retry by remember(key) { mutableStateOf(0) }
    LaunchedEffect(key, retry) {
        loadError = false
        val result = runCatching {
            withContext(Dispatchers.IO) { puzzleStore.loadOrNull(id) }
                ?: withContext(Dispatchers.Default) { generatePuzzle(id) }
                    .also { generated ->
                        withContext(Dispatchers.IO) { puzzleStore.savePuzzle(id.seed, generated) }
                    }
        }
        result.onSuccess { puzzle = it }.onFailure { loadError = true }
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
                val available = withContext(Dispatchers.IO) { puzzleStore.has(nextId.seed) }
                if (!available) {
                    val generated = runCatching {
                        withContext(Dispatchers.Default) { generatePuzzle(nextId) }
                    }.getOrNull() ?: break
                    withContext(Dispatchers.IO) { puzzleStore.savePuzzle(nextId.seed, generated) }
                }
                unsolved++
            }
            offset++
        }
    }

    val loaded = puzzle
    when {
        loaded != null -> GameContent(id, loaded, onBack, onNextLevel)
        loadError -> GenerationErrorScreen(id, onRetry = { retry++ }, onBack = onBack)
        else -> GeneratingScreen(id, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratingScreen(id: PuzzleId, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.game_title,
                            stringResource(id.difficulty.labelRes),
                            levelOf(id.difficulty, id.seed) + 1,
                        ),
                    )
                },
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
            Text(stringResource(R.string.generating_title), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.generating_body),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerationErrorScreen(id: PuzzleId, onRetry: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.game_title,
                            stringResource(id.difficulty.labelRes),
                            levelOf(id.difficulty, id.seed) + 1,
                        ),
                    )
                },
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
            Text(stringResource(R.string.error_title), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.error_body),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_to_levels)) }
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
    var busy by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
                    message = context.getString(R.string.solved_snackbar),
                    actionLabel = context.getString(R.string.next_level),
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
        if (busy) return
        val target = firstEmpty()
        if (target == null) {
            message = context.getString(R.string.msg_every_cell_filled)
            return
        }
        busy = true
        scope.launch {
            val completion = try {
                withContext(Dispatchers.Default) { solver.solveWith(grid.toMap()) }
            } catch (_: SolverLimitException) {
                busy = false
                message = context.getString(R.string.msg_solver_gave_up)
                return@launch
            }
            busy = false
            if (completion == null) {
                message = context.getString(R.string.msg_inconsistent_hint)
                return@launch
            }
            pushUndo()
            grid[target] = completion.getValue(target)
            notes.remove(target)
            selected = target
            message = context.getString(R.string.msg_hint_revealed)
        }
    }

    fun solveAll() {
        if (busy) return
        busy = true
        scope.launch {
            val completion = try {
                withContext(Dispatchers.Default) { solver.solveWith(grid.toMap()) }
            } catch (_: SolverLimitException) {
                busy = false
                message = context.getString(R.string.msg_solver_gave_up)
                return@launch
            }
            busy = false
            if (completion == null) {
                message = context.getString(R.string.msg_inconsistent_solve)
                return@launch
            }
            pushUndo()
            grid.clear()
            grid.putAll(completion)
            notes.clear()
            selected = null
            message = context.getString(R.string.msg_filled_solution)
        }
    }

    fun fillGivens() {
        val fills = solver.givenLetters().filter { (cell, ch) -> grid[cell] != ch }
        if (fills.isEmpty()) {
            message = context.getString(R.string.msg_no_givens)
            return
        }
        pushUndo()
        for ((cell, ch) in fills) {
            grid[cell] = ch
            notes.remove(cell)
        }
        message = context.resources.getQuantityString(R.plurals.givens_filled, fills.size, fills.size)
    }

    fun loadSavedSolution() {
        val stored = store.solvedGrid(id)
        if (stored == null) {
            message = context.getString(R.string.msg_no_saved_solution)
            return
        }
        pushUndo()
        grid.clear()
        grid.putAll(stored.filterKeys { it in validCells })
        notes.clear()
        selected = null
        message = context.getString(R.string.msg_loaded_solution)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(
                                R.string.game_title,
                                stringResource(id.difficulty.labelRes),
                                level + 1,
                            ),
                        )
                        Text(
                            formatElapsed(elapsed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Tip(stringResource(R.string.tooltip_back)) {
                        TextButton(onClick = onBack) {
                            Text("\u2190", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    Tip(stringResource(R.string.tooltip_undo)) {
                        TextButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                            Text("\u21B6", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Tip(stringResource(R.string.tooltip_redo)) {
                        TextButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) {
                            Text("\u21B7", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Tip(stringResource(R.string.tooltip_menu)) {
                        Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text("\u22EE", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_solve)) },
                                    enabled = !busy,
                                    onClick = {
                                        menuOpen = false
                                        confirmSolve = true
                                    },
                                )
                                if (solvedNow) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_load_saved)) },
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
                Tip(stringResource(R.string.tooltip_hint)) {
                    OutlinedButton(onClick = { hint() }, enabled = !busy) {
                        Text(stringResource(R.string.action_hint))
                    }
                }
                Tip(stringResource(R.string.tooltip_givens)) {
                    OutlinedButton(onClick = { fillGivens() }) {
                        Text(stringResource(R.string.action_givens))
                    }
                }
                if (notesMode) {
                    Tip(stringResource(R.string.tooltip_notes_on)) {
                        Button(onClick = { notesMode = false }) {
                            Text(stringResource(R.string.action_notes_on))
                        }
                    }
                } else {
                    Tip(stringResource(R.string.tooltip_notes)) {
                        OutlinedButton(onClick = { notesMode = true }) {
                            Text(stringResource(R.string.action_notes))
                        }
                    }
                }
                Tip(stringResource(R.string.tooltip_clear)) {
                    OutlinedButton(
                        onClick = { if (grid.isNotEmpty() || notes.isNotEmpty()) confirmClear = true },
                    ) { Text(stringResource(R.string.action_clear)) }
                }
            }
        }
    }

    if (confirmSolve) {
        AlertDialog(
            onDismissRequest = { confirmSolve = false },
            title = { Text(stringResource(R.string.solve_dialog_title)) },
            text = { Text(stringResource(R.string.solve_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSolve = false
                        solveAll()
                    },
                ) { Text(stringResource(R.string.action_solve)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSolve = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_dialog_title)) },
            text = { Text(stringResource(R.string.clear_dialog_body)) },
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
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
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
            text = stringResource(
                if (isHex) R.string.clue_tap_hex else R.string.clue_tap_rect,
            ),
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
            text = stringResource(
                if (isHex) R.string.clue_footnote_hex else R.string.clue_footnote_rect,
            ),
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
        result.solved -> stringResource(R.string.status_solved)
        result.complete ->
            pluralStringResource(R.plurals.status_failures, result.failures.size, result.failures.size)
        unfinished > 0 ->
            pluralStringResource(R.plurals.status_unfinished, unfinished, unfinished)
        else -> stringResource(R.string.status_fill_all)
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
