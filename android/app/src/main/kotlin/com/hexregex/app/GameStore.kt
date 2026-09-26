package com.hexregex.app

import android.content.Context
import com.hexregex.engine.Cell

/**
 * Persists the in-progress grid and selection per difficulty.
 *
 * Deliberately tiny: a SharedPreferences file with one record per difficulty,
 * so quitting and relaunching resumes exactly where the player left off.
 */
class GameStore(context: Context) {
    private val prefs = context.getSharedPreferences("hexregex", Context.MODE_PRIVATE)

    data class SavedState(
        val grid: Map<Cell, Char>,
        val notes: Map<Cell, Set<Char>>,
        val selected: Cell?,
    )

    fun loadDifficulty(fallback: Difficulty): Difficulty {
        val name = prefs.getString(KEY_DIFFICULTY, null) ?: return fallback
        return Difficulty.entries.firstOrNull { it.name == name } ?: fallback
    }

    fun saveDifficulty(difficulty: Difficulty) {
        prefs.edit().putString(KEY_DIFFICULTY, difficulty.name).apply()
    }

    fun load(difficulty: Difficulty): SavedState {
        val grid = decodeGrid(prefs.getString(gridKey(difficulty), null))
        val notes = decodeNotes(prefs.getString(notesKey(difficulty), null))
        val selected = decodeCell(prefs.getString(selectedKey(difficulty), null))
        return SavedState(grid, notes, selected)
    }

    fun save(
        difficulty: Difficulty,
        grid: Map<Cell, Char>,
        notes: Map<Cell, Set<Char>>,
        selected: Cell?,
    ) {
        prefs.edit()
            .putString(gridKey(difficulty), encodeGrid(grid))
            .putString(notesKey(difficulty), encodeNotes(notes))
            .putString(selectedKey(difficulty), selected?.let { encodeCell(it) })
            .apply()
    }

    private fun gridKey(difficulty: Difficulty) = "grid_${difficulty.name}"
    private fun notesKey(difficulty: Difficulty) = "notes_${difficulty.name}"
    private fun selectedKey(difficulty: Difficulty) = "selected_${difficulty.name}"

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
        const val KEY_DIFFICULTY = "difficulty"
    }
}
