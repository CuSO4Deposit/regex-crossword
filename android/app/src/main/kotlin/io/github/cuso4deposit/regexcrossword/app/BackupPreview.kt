package io.github.cuso4deposit.regexcrossword.app

data class DifficultyProgressSummary(
    val solved: Int,
    val inProgress: Int,
)

data class BackupComparison(
    val conflictingPuzzles: List<PuzzleId>,
    val addedPuzzles: List<PuzzleId>,
    val localOnlyPuzzles: List<PuzzleId>,
) {
    val conflicts: Int get() = conflictingPuzzles.size
    val added: Int get() = addedPuzzles.size
    val localOnly: Int get() = localOnlyPuzzles.size

    val matchesLocal: Boolean
        get() = conflicts == 0 && added == 0 && localOnly == 0
}

internal object BackupPreview {
    fun counts(payload: BackupPayload): Map<Difficulty, DifficultyProgressSummary> =
        Difficulty.entries.associateWith { difficulty ->
            val records = payload.progress.filter { it.puzzle.difficulty == difficulty }
            DifficultyProgressSummary(
                solved = records.count { it.completed },
                inProgress = records.count { !it.completed },
            )
        }

    fun compare(backup: BackupPayload, local: BackupPayload): BackupComparison {
        val backupById = backup.progress.associateBy { it.puzzle }
        val localById = local.progress.associateBy { it.puzzle }
        return BackupComparison(
            conflictingPuzzles = backupById.keys
                .filter { id ->
                    localById[id]?.let { it != backupById.getValue(id) } == true
                }
                .sortedWith(puzzleOrder),
            addedPuzzles = backupById.keys
                .filter { it !in localById }
                .sortedWith(puzzleOrder),
            localOnlyPuzzles = localById.keys
                .filter { it !in backupById }
                .sortedWith(puzzleOrder),
        )
    }

    private val puzzleOrder = compareBy<PuzzleId>(
        { it.version },
        { it.difficulty.ordinal },
        { it.seed },
    )
}
