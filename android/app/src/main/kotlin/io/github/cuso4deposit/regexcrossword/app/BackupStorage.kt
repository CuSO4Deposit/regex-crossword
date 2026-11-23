package io.github.cuso4deposit.regexcrossword.app

import android.content.Context
import android.content.SharedPreferences

class BackupStorage internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences("regexcrossword", Context.MODE_PRIVATE),
    )

    fun snapshot(): BackupPayload = SavedStateGate.read {
        val values = prefs.all
        for (key in values.keys) {
            if (!StorageKeys.isKnownKey(key)) {
                fail("unsupported local preference key '$key'")
            }
        }

        val identities = LinkedHashSet<PuzzleId>()
        for (key in values.keys) {
            StorageKeys.parseProgressKey(key)?.let { identities += it.puzzle }
        }

        val progress = identities.map { id ->
            val completed = booleanValue(values, StorageKeys.solved(id), "solved state") ?: false
            val grid = SavedStateCodec.decodeGrid(
                stringValue(values, StorageKeys.grid(id), "grid"),
            )
            val notes = SavedStateCodec.decodeNotes(
                stringValue(values, StorageKeys.notes(id), "notes"),
            )
            val selected = SavedStateCodec.decodeCell(
                stringValue(values, StorageKeys.selected(id), "selected cell"),
            )
                ?.takeIf { isBoardCell(id.difficulty, it) }
            val solution =
                if (completed) {
                    SavedStateCodec.decodeGrid(
                        stringValue(values, StorageKeys.solution(id), "solution"),
                    ).also {
                        if (it.isEmpty()) fail("completed puzzle $id has no stored solution")
                    }
                } else {
                    null
                }
            BackupProgress(
                puzzle = id,
                grid = grid,
                notes = notes,
                selected = selected,
                completed = completed,
                solution = solution,
                elapsedSeconds = elapsedValue(values[StorageKeys.time(id)]),
            )
        }

        val payload = BackupPayload(
            progress = progress,
            lastPosition = readPosition(values),
            keyboardLayout = readKeyboardLayout(values),
        )
        BackupValidator.validate(payload)
        payload
    }

    fun restore(payload: BackupPayload): Boolean {
        BackupValidator.validate(payload)
        return SavedStateGate.replace(prefs) {
            clear()
            for (progress in payload.progress) {
                val id = progress.puzzle
                putString(StorageKeys.grid(id), SavedStateCodec.encodeGrid(progress.grid))
                putString(StorageKeys.notes(id), SavedStateCodec.encodeNotes(progress.notes))
                progress.selected?.let {
                    putString(StorageKeys.selected(id), SavedStateCodec.encodeCell(it))
                }
                if (progress.completed) {
                    putBoolean(StorageKeys.solved(id), true)
                    putString(
                        StorageKeys.solution(id),
                        SavedStateCodec.encodeGrid(progress.solution.orEmpty()),
                    )
                }
                putLong(StorageKeys.time(id), progress.elapsedSeconds)
            }
            payload.lastPosition?.let {
                putInt(StorageKeys.POSITION_VERSION, it.version)
                putString(StorageKeys.POSITION_DIFFICULTY, it.difficulty.name)
                putInt(StorageKeys.POSITION_SEED, it.seed)
            }
            putString(StorageKeys.KEYBOARD_LAYOUT, payload.keyboardLayout.name)
        }
    }

    private fun readPosition(values: Map<String, *>): PuzzleId? {
        val keys = setOf(
            StorageKeys.POSITION_VERSION,
            StorageKeys.POSITION_DIFFICULTY,
            StorageKeys.POSITION_SEED,
        )
        val present = keys.filter { it in values }
        if (present.isEmpty()) return null
        if (present.size != keys.size) fail("the stored last position is incomplete")
        val version = intValue(values[StorageKeys.POSITION_VERSION], "position version")
        val difficultyName = values[StorageKeys.POSITION_DIFFICULTY] as? String
            ?: fail("position difficulty is invalid")
        val difficulty = Difficulty.entries.firstOrNull { it.name == difficultyName }
            ?: fail("position difficulty '$difficultyName' is invalid")
        val seed = intValue(values[StorageKeys.POSITION_SEED], "position seed")
        if (version < 0 || seed < 0) fail("stored last position is out of range")
        return PuzzleId(version, difficulty, seed)
    }

    private fun readKeyboardLayout(values: Map<String, *>): KeyboardLayout {
        val raw = values[StorageKeys.KEYBOARD_LAYOUT] ?: return KeyboardLayout.default
        if (raw !is String) fail("stored keyboard layout is invalid")
        return KeyboardLayout.entries.firstOrNull { it.name == raw }
            ?: fail("stored keyboard layout '$raw' is invalid")
    }

    private fun elapsedValue(value: Any?): Long =
        when (value) {
            null -> 0L
            is Long -> value
            is Int -> value.toLong()
            else -> fail("stored elapsed time is invalid")
        }.also {
            if (it < 0) fail("stored elapsed time is negative")
        }

    private fun stringValue(values: Map<String, *>, key: String, label: String): String? {
        val value = values[key] ?: return null
        return value as? String ?: fail("stored $label is invalid")
    }

    private fun booleanValue(values: Map<String, *>, key: String, label: String): Boolean? {
        val value = values[key] ?: return null
        return value as? Boolean ?: fail("stored $label is invalid")
    }

    private fun intValue(value: Any?, label: String): Int =
        when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                ?: fail("$label is out of range")
            else -> fail("$label is invalid")
        }

    private fun fail(message: String): Nothing = throw BackupFormatException(message)
}
