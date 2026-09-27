package io.github.cuso4deposit.regexcrossword.app

import android.content.Context
import io.github.cuso4deposit.regexcrossword.engine.Cell

/**
 * Persists progress keyed by puzzle identity `(version, difficulty, seed)`,
 * not by level number. The key literally records the generator version and the
 * true seed, so a version bump can never make an old record collide with a
 * different puzzle. Every change is written with a synchronous `commit()` so an
 * accidental crash cannot lose the player's work.
 */
class GameStore(context: Context) {
    private val prefs = context.getSharedPreferences("regexcrossword", Context.MODE_PRIVATE)

    data class SavedState(
        val grid: Map<Cell, Char>,
        val notes: Map<Cell, Set<Char>>,
        val selected: Cell?,
    )

    fun load(id: PuzzleId): SavedState {
        val grid = decodeGrid(prefs.getString(gridKey(id), null))
        val notes = decodeNotes(prefs.getString(notesKey(id), null))
        val selected = decodeCell(prefs.getString(selectedKey(id), null))
        return SavedState(grid, notes, selected)
    }

    fun save(id: PuzzleId, grid: Map<Cell, Char>, notes: Map<Cell, Set<Char>>, selected: Cell?) {
        prefs.edit()
            .putString(gridKey(id), encodeGrid(grid))
            .putString(notesKey(id), encodeNotes(notes))
            .putString(selectedKey(id), selected?.let { encodeCell(it) })
            .commit()
    }

    /** Mark a puzzle solved and remember the grid that solved it. */
    fun markSolved(id: PuzzleId, grid: Map<Cell, Char>) {
        prefs.edit()
            .putBoolean(solvedKey(id), true)
            .putString(solutionKey(id), encodeGrid(grid))
            .commit()
    }

    fun isSolved(id: PuzzleId): Boolean = prefs.getBoolean(solvedKey(id), false)

    /** Seconds the player has spent on this puzzle so far. */
    fun elapsedSeconds(id: PuzzleId): Long = prefs.getLong(timeKey(id), 0L)

    fun saveElapsedSeconds(id: PuzzleId, seconds: Long) {
        prefs.edit().putLong(timeKey(id), seconds).commit()
    }

    /** The stored winning grid for a solved puzzle, if any. */
    fun solvedGrid(id: PuzzleId): Map<Cell, Char>? =
        prefs.getString(solutionKey(id), null)?.let { decodeGrid(it) }

    /**
     * Level numbers solved for a difficulty under the **current** generator
     * version; seeds are read from the keys and mapped back through [levelOf].
     */
    fun solvedLevels(difficulty: Difficulty): Set<Int> {
        val prefix = "solved_${basePrefix(difficulty)}"
        val out = HashSet<Int>()
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix)) {
                val seed = key.removePrefix(prefix).toIntOrNull() ?: continue
                val level = levelOf(difficulty, seed)
                if (level >= 0) out.add(level)
            }
        }
        return out
    }

    fun savePosition(id: PuzzleId) {
        prefs.edit()
            .putInt(KEY_POSITION_VERSION, id.version)
            .putString(KEY_POSITION_DIFFICULTY, id.difficulty.name)
            .putInt(KEY_POSITION_SEED, id.seed)
            .commit()
    }

    /** The last puzzle identity, or null if none / from another version. */
    fun loadPosition(): PuzzleId? {
        if (!prefs.contains(KEY_POSITION_SEED)) return null
        val version = prefs.getInt(KEY_POSITION_VERSION, -1)
        val difficulty = prefs.getString(KEY_POSITION_DIFFICULTY, null)
            ?.let { name -> Difficulty.entries.firstOrNull { it.name == name } }
            ?: return null
        val seed = prefs.getInt(KEY_POSITION_SEED, -1)
        if (version != GENERATOR_VERSION || seed < 0) return null
        return PuzzleId(version, difficulty, seed)
    }

    private fun basePrefix(difficulty: Difficulty) = "gv${GENERATOR_VERSION}_${difficulty.name}_"

    private fun base(id: PuzzleId) = "gv${id.version}_${id.difficulty.name}_${id.seed}"

    private fun gridKey(id: PuzzleId) = "grid_${base(id)}"
    private fun notesKey(id: PuzzleId) = "notes_${base(id)}"
    private fun selectedKey(id: PuzzleId) = "selected_${base(id)}"
    private fun solvedKey(id: PuzzleId) = "solved_${base(id)}"
    private fun solutionKey(id: PuzzleId) = "solution_${base(id)}"
    private fun timeKey(id: PuzzleId) = "time_${base(id)}"

    private fun encodeGrid(grid: Map<Cell, Char>): String =
        grid.entries.joinToString(";") { "${it.key.r},${it.key.c},${it.value}" }

    private fun decodeGrid(raw: String?): Map<Cell, Char> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<Cell, Char>()
        for (part in raw.split(';')) {
            val fields = part.split(',')
            if (fields.size != 3) continue
            val r = fields[0].toIntOrNull() ?: continue
            val c = fields[1].toIntOrNull() ?: continue
            val ch = fields[2].firstOrNull() ?: continue
            out[Cell(r, c)] = ch
        }
        return out
    }

    private fun encodeNotes(notes: Map<Cell, Set<Char>>): String =
        notes.entries.joinToString(";") { (cell, letters) ->
            "${cell.r},${cell.c},${letters.sorted().joinToString("")}"
        }

    private fun decodeNotes(raw: String?): Map<Cell, Set<Char>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<Cell, Set<Char>>()
        for (part in raw.split(';')) {
            val fields = part.split(',')
            if (fields.size != 3) continue
            val r = fields[0].toIntOrNull() ?: continue
            val c = fields[1].toIntOrNull() ?: continue
            val letters = fields[2].toSet()
            if (letters.isNotEmpty()) out[Cell(r, c)] = letters
        }
        return out
    }

    private fun encodeCell(cell: Cell): String = "${cell.r},${cell.c}"

    private fun decodeCell(raw: String?): Cell? {
        if (raw.isNullOrEmpty()) return null
        val fields = raw.split(',')
        if (fields.size != 2) return null
        val r = fields[0].toIntOrNull() ?: return null
        val c = fields[1].toIntOrNull() ?: return null
        return Cell(r, c)
    }

    private companion object {
        const val KEY_POSITION_VERSION = "position_version"
        const val KEY_POSITION_DIFFICULTY = "position_difficulty"
        const val KEY_POSITION_SEED = "position_seed"
    }
}
