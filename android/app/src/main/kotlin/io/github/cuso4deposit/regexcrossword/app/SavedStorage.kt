package io.github.cuso4deposit.regexcrossword.app

import android.annotation.SuppressLint
import android.content.SharedPreferences
import io.github.cuso4deposit.regexcrossword.engine.Cell

internal object SavedStateGate {
    private val lock = Any()

    @Volatile
    private var epoch = 0L

    @Volatile
    private var restoring = false

    fun currentEpoch(): Long = epoch

    fun <T> read(block: () -> T): T = synchronized(lock) { block() }

    @SuppressLint("ApplySharedPref")
    fun write(
        prefs: SharedPreferences,
        expectedEpoch: Long,
        block: SharedPreferences.Editor.() -> Unit,
    ): Boolean {
        if (restoring || epoch != expectedEpoch) return false
        return synchronized(lock) {
            if (restoring || epoch != expectedEpoch) {
                false
            } else {
                prefs.edit().apply(block).commit()
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    fun replace(prefs: SharedPreferences, block: SharedPreferences.Editor.() -> Unit): Boolean =
        synchronized(lock) {
            restoring = true
            epoch++
            try {
                prefs.edit().apply(block).commit()
            } finally {
                restoring = false
            }
        }
}

internal object StorageKeys {
    const val POSITION_VERSION = "position_version"
    const val POSITION_DIFFICULTY = "position_difficulty"
    const val POSITION_SEED = "position_seed"
    const val KEYBOARD_LAYOUT = "keyboard_layout"

    private val progressPattern =
        Regex("^(grid|notes|selected|solved|solution|time)_gv([0-9]+)_(EASY|MEDIUM|HARD)_([0-9]+)$")

    data class ProgressKey(val field: String, val puzzle: PuzzleId)

    fun grid(id: PuzzleId) = "grid_${identity(id)}"
    fun notes(id: PuzzleId) = "notes_${identity(id)}"
    fun selected(id: PuzzleId) = "selected_${identity(id)}"
    fun solved(id: PuzzleId) = "solved_${identity(id)}"
    fun solution(id: PuzzleId) = "solution_${identity(id)}"
    fun time(id: PuzzleId) = "time_${identity(id)}"

    fun parseProgressKey(key: String): ProgressKey? {
        val match = progressPattern.matchEntire(key) ?: return null
        val version = match.groupValues[2].toIntOrNull() ?: return null
        val difficulty = Difficulty.entries.firstOrNull { it.name == match.groupValues[3] } ?: return null
        val seed = match.groupValues[4].toIntOrNull() ?: return null
        return ProgressKey(match.groupValues[1], PuzzleId(version, difficulty, seed))
    }

    fun isKnownKey(key: String): Boolean =
        key == POSITION_VERSION ||
            key == POSITION_DIFFICULTY ||
            key == POSITION_SEED ||
            key == KEYBOARD_LAYOUT ||
            parseProgressKey(key) != null

    private fun identity(id: PuzzleId) = "gv${id.version}_${id.difficulty.name}_${id.seed}"
}

internal object SavedStateCodec {
    fun encodeGrid(grid: Map<Cell, Char>): String =
        grid.entries
            .sortedWith(compareBy({ it.key.r }, { it.key.c }))
            .joinToString(";") { "${it.key.r},${it.key.c},${it.value}" }

    fun decodeGrid(raw: String?): Map<Cell, Char> {
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

    fun encodeNotes(notes: Map<Cell, Set<Char>>): String =
        notes.entries
            .sortedWith(compareBy({ it.key.r }, { it.key.c }))
            .joinToString(";") { (cell, letters) ->
                "${cell.r},${cell.c},${letters.sorted().joinToString("")}"
            }

    fun decodeNotes(raw: String?): Map<Cell, Set<Char>> {
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

    fun encodeCell(cell: Cell): String = "${cell.r},${cell.c}"

    fun decodeCell(raw: String?): Cell? {
        if (raw.isNullOrEmpty()) return null
        val fields = raw.split(',')
        if (fields.size != 2) return null
        val r = fields[0].toIntOrNull() ?: return null
        val c = fields[1].toIntOrNull() ?: return null
        return Cell(r, c)
    }
}
