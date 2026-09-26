package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.ui.graphics.Color

/**
 * Distinct colours so the X / Y / Z line families are distinguishable.
 *
 * [label] is the strong colour used for badges, arrows and start rings.
 * [tint] is the same colour at low alpha, so it composites against whatever
 * the app background is: it looks like a light pastel in light mode and a dark
 * shade in dark mode, keeping the (theme-coloured) letters readable on top.
 */
object FamilyColors {
    private val X = Color(0xFF1565C0)
    private val Y = Color(0xFF2E7D32)
    private val Z = Color(0xFFC62828)

    fun label(family: String): Color = when (family) {
        "x" -> X
        "y" -> Y
        else -> Z
    }

    fun tint(family: String): Color = label(family).copy(alpha = 0.28f)
}
