package io.github.cuso4deposit.regexcrossword.app

import androidx.annotation.StringRes

private const val MAX_KEY_COLUMNS = 10
private const val ALPHABET_SIZE = 26

/**
 * Letter arrangements for the on-screen palette.
 *
 * [rows] is empty for the alphabetical palette, which keeps the original
 * wrapping layout. Every other layout lists its rows top to bottom; rows may
 * have different lengths (QWERTY is 10/9/7, AZERTY is 10/10/6).
 */
enum class KeyboardLayout(
    @StringRes val labelRes: Int,
    val rows: List<String>,
) {
    ALPHABETICAL(R.string.keyboard_layout_alphabetical, emptyList()),
    QWERTY(
        R.string.keyboard_layout_qwerty,
        listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"),
    ),
    QWERTZ(
        R.string.keyboard_layout_qwertz,
        listOf("QWERTZUIOP", "ASDFGHJKL", "YXCVBNM"),
    ),
    AZERTY(
        R.string.keyboard_layout_azerty,
        listOf("AZERTYUIOP", "QSDFGHJKLM", "WXCVBN"),
    ),
    DVORAK(
        R.string.keyboard_layout_dvorak,
        listOf("PYFGCRL", "AOEUIDHTNS", "QJKXBMWVZ"),
    ),
    COLEMAK(
        R.string.keyboard_layout_colemak,
        listOf("QWFPGJLUY", "ARSTDHNEIO", "ZXCVBKM"),
    ),
    ;

    val wrapped: Boolean get() = rows.isEmpty()

    init {
        if (rows.isNotEmpty()) {
            val letters = rows.joinToString("")
            require(rows.all { it.length <= MAX_KEY_COLUMNS }) {
                "$name has a row wider than $MAX_KEY_COLUMNS keys"
            }
            require(letters.length == ALPHABET_SIZE && letters.toSet().size == ALPHABET_SIZE) {
                "$name must contain each letter exactly once"
            }
        }
    }

    companion object {
        const val MAX_COLUMNS = MAX_KEY_COLUMNS
        val default: KeyboardLayout = ALPHABETICAL
    }
}
