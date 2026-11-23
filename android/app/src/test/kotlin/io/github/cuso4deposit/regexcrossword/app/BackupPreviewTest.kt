package io.github.cuso4deposit.regexcrossword.app

import io.github.cuso4deposit.regexcrossword.engine.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreviewTest {
    @Test
    fun countsSolvedAndInProgressRecordsForEveryDifficulty() {
        val payload = payload(
            record(Difficulty.EASY, 1_000_000, completed = true),
            record(Difficulty.EASY, 1_000_001, completed = false),
            record(Difficulty.MEDIUM, 2_000_000, completed = false),
            record(Difficulty.HARD, 3_000_000, completed = true),
        )

        val counts = BackupPreview.counts(payload)

        assertEquals(DifficultyProgressSummary(solved = 1, inProgress = 1), counts[Difficulty.EASY])
        assertEquals(
            DifficultyProgressSummary(solved = 0, inProgress = 1),
            counts[Difficulty.MEDIUM],
        )
        assertEquals(DifficultyProgressSummary(solved = 1, inProgress = 0), counts[Difficulty.HARD])
    }

    @Test
    fun comparisonDistinguishesConflictsAdditionsAndLocalOnlyRecords() {
        val conflictId = PuzzleId(GENERATOR_VERSION, Difficulty.EASY, 1_000_000)
        val sameId = PuzzleId(GENERATOR_VERSION, Difficulty.MEDIUM, 2_000_000)
        val backupOnlyId = PuzzleId(GENERATOR_VERSION, Difficulty.HARD, 3_000_000)
        val localOnlyId = PuzzleId(GENERATOR_VERSION, Difficulty.EASY, 1_000_001)
        val backup = payload(
            record(conflictId, elapsedSeconds = 20),
            record(sameId, elapsedSeconds = 30),
            record(backupOnlyId, completed = true),
        )
        val local = payload(
            record(conflictId, elapsedSeconds = 10),
            record(sameId, elapsedSeconds = 30),
            record(localOnlyId, elapsedSeconds = 40),
        )

        val comparison = BackupPreview.compare(backup, local)

        assertEquals(1, comparison.conflicts)
        assertEquals(1, comparison.added)
        assertEquals(1, comparison.localOnly)
        assertEquals(listOf(conflictId), comparison.conflictingPuzzles)
        assertEquals(listOf(backupOnlyId), comparison.addedPuzzles)
        assertEquals(listOf(localOnlyId), comparison.localOnlyPuzzles)
        assertTrue(!comparison.matchesLocal)
        assertTrue(BackupPreview.compare(backup, backup).matchesLocal)
    }

    private fun payload(vararg progress: BackupProgress): BackupPayload =
        BackupPayload(
            progress = progress.toList(),
            lastPosition = null,
            keyboardLayout = KeyboardLayout.default,
        )

    private fun record(
        difficulty: Difficulty,
        seed: Int,
        completed: Boolean = false,
    ): BackupProgress =
        record(PuzzleId(GENERATOR_VERSION, difficulty, seed), completed = completed)

    private fun record(
        puzzle: PuzzleId,
        elapsedSeconds: Long = 0,
        completed: Boolean = false,
    ): BackupProgress {
        val answer = mapOf(Cell(0, 0) to 'A')
        return BackupProgress(
            puzzle = puzzle,
            grid = if (completed) answer else emptyMap(),
            notes = emptyMap(),
            selected = null,
            completed = completed,
            solution = if (completed) answer else null,
            elapsedSeconds = elapsedSeconds,
        )
    }
}
