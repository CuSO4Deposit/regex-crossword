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
    private val writeEpoch = SavedStateGate.currentEpoch()

    data class SavedState(
        val grid: Map<Cell, Char>,
        val notes: Map<Cell, Set<Char>>,
        val selected: Cell?,
    )

    fun load(id: PuzzleId): SavedState = SavedStateGate.read {
        val grid = SavedStateCodec.decodeGrid(prefs.getString(StorageKeys.grid(id), null))
        val notes = SavedStateCodec.decodeNotes(prefs.getString(StorageKeys.notes(id), null))
        val selected = SavedStateCodec.decodeCell(prefs.getString(StorageKeys.selected(id), null))
        SavedState(grid, notes, selected)
    }

    fun save(id: PuzzleId, grid: Map<Cell, Char>, notes: Map<Cell, Set<Char>>, selected: Cell?) {
        SavedStateGate.write(prefs, writeEpoch) {
            putString(StorageKeys.grid(id), SavedStateCodec.encodeGrid(grid))
            putString(StorageKeys.notes(id), SavedStateCodec.encodeNotes(notes))
            putString(StorageKeys.selected(id), selected?.let(SavedStateCodec::encodeCell))
        }
    }

    /** Mark a puzzle solved and remember the grid that solved it. */
    fun markSolved(id: PuzzleId, grid: Map<Cell, Char>) {
        SavedStateGate.write(prefs, writeEpoch) {
            putBoolean(StorageKeys.solved(id), true)
            putString(StorageKeys.solution(id), SavedStateCodec.encodeGrid(grid))
        }
    }

    fun isSolved(id: PuzzleId): Boolean =
        SavedStateGate.read { prefs.getBoolean(StorageKeys.solved(id), false) }

    /** Seconds the player has spent on this puzzle so far. */
    fun elapsedSeconds(id: PuzzleId): Long =
        SavedStateGate.read { prefs.getLong(StorageKeys.time(id), 0L) }

    fun saveElapsedSeconds(id: PuzzleId, seconds: Long) {
        SavedStateGate.write(prefs, writeEpoch) {
            putLong(StorageKeys.time(id), seconds)
        }
    }

    /** The stored winning grid for a solved puzzle, if any. */
    fun solvedGrid(id: PuzzleId): Map<Cell, Char>? = SavedStateGate.read {
        prefs.getString(StorageKeys.solution(id), null)?.let(SavedStateCodec::decodeGrid)
    }

    /**
     * Level numbers solved for a difficulty under the **current** generator
     * version; seeds are read from the keys and mapped back through [levelOf].
     */
    fun solvedLevels(difficulty: Difficulty): Set<Int> = SavedStateGate.read {
        val out = HashSet<Int>()
        for ((key, value) in prefs.all) {
            val progress = StorageKeys.parseProgressKey(key) ?: continue
            if (
                progress.field == "solved" &&
                value == true &&
                progress.puzzle.version == GENERATOR_VERSION &&
                progress.puzzle.difficulty == difficulty
            ) {
                val level = levelOf(difficulty, progress.puzzle.seed)
                if (level >= 0) out.add(level)
            }
        }
        out
    }

    fun savePosition(id: PuzzleId) {
        SavedStateGate.write(prefs, writeEpoch) {
            putInt(StorageKeys.POSITION_VERSION, id.version)
            putString(StorageKeys.POSITION_DIFFICULTY, id.difficulty.name)
            putInt(StorageKeys.POSITION_SEED, id.seed)
        }
    }

    /** The last puzzle identity, or null if none / from another version. */
    fun loadPosition(): PuzzleId? = SavedStateGate.read {
        if (!prefs.contains(StorageKeys.POSITION_SEED)) return@read null
        val version = prefs.getInt(StorageKeys.POSITION_VERSION, -1)
        val difficulty = prefs.getString(StorageKeys.POSITION_DIFFICULTY, null)
            ?.let { name -> Difficulty.entries.firstOrNull { it.name == name } }
            ?: return@read null
        val seed = prefs.getInt(StorageKeys.POSITION_SEED, -1)
        if (version != GENERATOR_VERSION || seed < 0) return@read null
        PuzzleId(version, difficulty, seed)
    }
}
