package io.github.cuso4deposit.regexcrossword.app

import io.github.cuso4deposit.regexcrossword.engine.GenConfig
import io.github.cuso4deposit.regexcrossword.engine.Generator
import io.github.cuso4deposit.regexcrossword.engine.Puzzle

/**
 * Generation identity.
 *
 * A puzzle is identified by **(GENERATOR_VERSION, seed)**: the seed alone
 * decides the RNG output, and the version says which generation logic (and
 * which level↔seed mapping) that seed belongs to. Level numbers are only a UI
 * convenience — under one version there is a bijection between level and seed
 * for each difficulty ([seedFor] / [levelOf]).
 *
 * Progress is keyed by the identity, never by the level number, so bumping
 * [GENERATOR_VERSION] cannot silently point an old save at a different puzzle.
 * Bump it on *any* change to the generator algorithm, the pinned presets, or
 * the seed bases.
 */
const val GENERATOR_VERSION = 4

data class PuzzleId(val version: Int, val difficulty: Difficulty, val seed: Int)

/**
 * Pinned presets. `targetScore` drives the clue opacity (higher = fewer
 * literals = fewer directly-fillable cells). medium is the old "hard"
 * (target ≈ 73); hard is looser (target ≈ 85, ~15% literal tokens).
 */
enum class Difficulty(
    val label: String,
    val tier: String,
    val seedBase: Int,
    val targetScore: Double?,
) {
    EASY("Easy", "easy", 1_000_000, null),
    MEDIUM("Medium", "medium", 2_000_000, 73.0),
    HARD("Hard", "hard", 3_000_000, 85.0),
}

/** The versioned level -> seed bijection for one difficulty. */
fun seedFor(difficulty: Difficulty, level: Int): Int = difficulty.seedBase + level

/** Inverse of [seedFor]; `-1` when the seed is outside this difficulty's range. */
fun levelOf(difficulty: Difficulty, seed: Int): Int {
    val level = seed - difficulty.seedBase
    return if (level >= 0) level else -1
}

fun puzzleIdFor(difficulty: Difficulty, level: Int): PuzzleId =
    PuzzleId(GENERATOR_VERSION, difficulty, seedFor(difficulty, level))

fun generatePuzzle(id: PuzzleId): Puzzle {
    require(id.version == GENERATOR_VERSION) {
        "cannot generate a level from generator version ${id.version}"
    }
    return Generator.constructive(
        GenConfig(
            edge = 5,
            difficulty = id.difficulty.tier,
            seed = id.seed,
            targetScore = id.difficulty.targetScore,
        ),
    )
}
