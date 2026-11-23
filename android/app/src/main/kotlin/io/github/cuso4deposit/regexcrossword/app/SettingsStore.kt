package io.github.cuso4deposit.regexcrossword.app

import android.content.Context

/** User preferences that are not tied to a particular puzzle. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("regexcrossword", Context.MODE_PRIVATE)
    private val writeEpoch = SavedStateGate.currentEpoch()

    var keyboardLayout: KeyboardLayout
        get() = SavedStateGate.read {
            prefs.getString(StorageKeys.KEYBOARD_LAYOUT, null)
                ?.let { name -> KeyboardLayout.entries.firstOrNull { it.name == name } }
                ?: KeyboardLayout.default
        }
        set(value) {
            SavedStateGate.write(prefs, writeEpoch) {
                putString(StorageKeys.KEYBOARD_LAYOUT, value.name)
            }
        }
}
