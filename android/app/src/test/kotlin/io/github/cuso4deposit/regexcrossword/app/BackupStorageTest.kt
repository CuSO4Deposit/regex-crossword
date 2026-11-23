package io.github.cuso4deposit.regexcrossword.app

import android.content.SharedPreferences
import io.github.cuso4deposit.regexcrossword.engine.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStorageTest {
    @Test
    fun emptyStoreProducesAnEmptyPayloadWithDefaultSettings() {
        val payload = BackupStorage(MemoryPreferences()).snapshot()

        assertEquals(emptyList<BackupProgress>(), payload.progress)
        assertNull(payload.lastPosition)
        assertEquals(KeyboardLayout.default, payload.keyboardLayout)
    }

    @Test
    fun restoreReplacesEverythingInOneCommitAndInvalidatesOldWriters() {
        val prefs = MemoryPreferences()
        val local = PuzzleId(6, Difficulty.EASY, 1_000_000)
        val restored = PuzzleId(5, Difficulty.HARD, 3_000_000)
        prefs.edit()
            .putString(StorageKeys.grid(local), "0,0,A")
            .putString(StorageKeys.notes(local), "0,1,BC")
            .putString(StorageKeys.selected(local), "0,0")
            .putBoolean(StorageKeys.solved(local), true)
            .putString(StorageKeys.solution(local), "0,0,A")
            .putLong(StorageKeys.time(local), 99)
            .putInt(StorageKeys.POSITION_VERSION, 6)
            .putString(StorageKeys.POSITION_DIFFICULTY, Difficulty.EASY.name)
            .putInt(StorageKeys.POSITION_SEED, local.seed)
            .putString(StorageKeys.KEYBOARD_LAYOUT, KeyboardLayout.QWERTY.name)
            .putString("unrelated_local_key", "remove me")
            .commit()
        prefs.clearCommitCount()
        val oldEpoch = SavedStateGate.currentEpoch()
        val storage = BackupStorage(prefs)
        val payload = BackupPayload(
            progress = listOf(
                BackupProgress(
                    puzzle = restored,
                    grid = mapOf(Cell(0, 0) to 'A', Cell(1, 0) to 'B'),
                    notes = mapOf(Cell(2, 1) to setOf('X', 'Y')),
                    selected = Cell(3, 2),
                    completed = true,
                    solution = mapOf(Cell(0, 0) to 'A', Cell(1, 0) to 'B'),
                    elapsedSeconds = 123,
                ),
            ),
            lastPosition = restored,
            keyboardLayout = KeyboardLayout.COLEMAK,
        )

        assertTrue(storage.restore(payload))

        assertEquals(1, prefs.commitCount)
        assertFalse(prefs.contains(StorageKeys.grid(local)))
        assertFalse(prefs.contains("unrelated_local_key"))
        assertEquals(payload, storage.snapshot())
        assertFalse(
            SavedStateGate.write(prefs, oldEpoch) {
                putString("late_write", "must be rejected")
            },
        )
        assertNull(prefs.getString("late_write", null))
    }

    @Test
    fun failedCommitDoesNotReportSuccessOrReplaceData() {
        val prefs = MemoryPreferences()
        val local = PuzzleId(6, Difficulty.EASY, 1_000_000)
        prefs.edit()
            .putString(StorageKeys.grid(local), "0,0,A")
            .putLong(StorageKeys.time(local), 10)
            .commit()
        prefs.clearCommitCount()
        prefs.failCommits = true

        val restored = BackupStorage(prefs).restore(
            BackupPayload(
                progress = emptyList(),
                lastPosition = null,
                keyboardLayout = KeyboardLayout.DVORAK,
            ),
        )

        assertFalse(restored)
        assertEquals("0,0,A", prefs.getString(StorageKeys.grid(local), null))
        assertEquals(0, prefs.commitCount)
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()
    var commitCount = 0
        private set
    var failCommits = false

    fun clearCommitCount() {
        commitCount = 0
    }

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (values[key] as? MutableSet<String>)?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = put(key, values?.toMutableSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            updates.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            updates.clear()
            removals.clear()
            return this
        }

        override fun commit(): Boolean {
            if (failCommits) return false
            if (clear) this@MemoryPreferences.values.clear()
            removals.forEach(this@MemoryPreferences.values::remove)
            this@MemoryPreferences.values.putAll(updates)
            this@MemoryPreferences.commitCount++
            listeners.forEach { listener ->
                updates.keys.forEach { key ->
                    listener.onSharedPreferenceChanged(this@MemoryPreferences, key)
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            removals -= key
            updates[key] = value
            return this
        }
    }
}
