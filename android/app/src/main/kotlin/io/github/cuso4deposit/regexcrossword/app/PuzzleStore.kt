package io.github.cuso4deposit.regexcrossword.app

import android.content.Context
import io.github.cuso4deposit.regexcrossword.engine.MiniJson
import io.github.cuso4deposit.regexcrossword.engine.Puzzle
import java.io.File

/**
 * Serves unique HARD puzzles: first from the bundled bank asset, then from a
 * local file cache, and fills the cache by on-device generation.
 *
 * Bank layout: `{"version":..,"base":B,"puzzles":[puzzle,..]}` where
 * `puzzles[i]` is the unique puzzle for `seed = B + i` (so level `L` uses
 * index `L` because HARD's seed base equals the bank base).
 */
class PuzzleStore(private val context: Context) {
    private var bank: List<Map<String, Any?>>? = null
    private var bankBase: Int = 3_000_000

    private fun bank(): List<Map<String, Any?>> {
        bank?.let { return it }
        val loaded = try {
            val text = context.assets.open("hard_bank.json").bufferedReader().readText()
            val root = MiniJson.parseObject(text)
            val version = (root["version"] as? Number)?.toInt()
            if (version != GENERATOR_VERSION) {
                // A stale bank must never be served: progress keys and puzzles
                // are keyed by GENERATOR_VERSION.
                emptyList()
            } else {
                bankBase = (root["base"] as? Number)?.toInt() ?: 3_000_000
                (root["puzzles"] as? List<*>)
                    ?.mapNotNull { it as? Map<String, Any?> }
                    ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        bank = loaded
        return loaded
    }

    private fun bankPuzzle(seed: Int): Puzzle? {
        val list = bank()
        val index = seed - bankBase
        if (index !in list.indices) return null
        return try {
            Puzzle.fromMap(list[index])
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheFile(seed: Int) = File(context.filesDir, "hard/$seed.json")

    fun cachedPuzzle(seed: Int): Puzzle? = try {
        val file = cacheFile(seed)
        if (file.exists()) Puzzle.fromJson(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun savePuzzle(seed: Int, puzzle: Puzzle) {
        val file = cacheFile(seed)
        file.parentFile?.mkdirs()
        file.writeText(puzzle.toJson())
    }

    /** Whether a puzzle for this seed is already available offline. */
    fun has(seed: Int): Boolean = bankPuzzle(seed) != null || cachedPuzzle(seed) != null

    /** The puzzle to show immediately, or null when it must be generated. */
    fun loadOrNull(id: PuzzleId): Puzzle? =
        if (id.difficulty.unique) bankPuzzle(id.seed) ?: cachedPuzzle(id.seed) else null
}
