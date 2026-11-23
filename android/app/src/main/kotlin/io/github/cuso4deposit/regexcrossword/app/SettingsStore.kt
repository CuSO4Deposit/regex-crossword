package io.github.cuso4deposit.regexcrossword.app

import android.content.Context

/** User preferences that are not tied to a particular puzzle. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("regexcrossword", Context.MODE_PRIVATE)

    var keyboardLayout: KeyboardLayout
        get() = prefs.getString(KEY_KEYBOARD_LAYOUT, null)
            ?.let { name -> KeyboardLayout.entries.firstOrNull { it.name == name } }
            ?: KeyboardLayout.default
        set(value) {
            prefs.edit().putString(KEY_KEYBOARD_LAYOUT, value.name).commit()
        }

    private companion object {
        const val KEY_KEYBOARD_LAYOUT = "keyboard_layout"
    }
}
