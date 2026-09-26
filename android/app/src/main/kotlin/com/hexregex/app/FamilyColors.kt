package com.hexregex.app

import androidx.compose.ui.graphics.Color

/** Distinct colours so the X / Y / Z line families are distinguishable. */
object FamilyColors {
    private val X = Color(0xFF1565C0)
    private val Y = Color(0xFF2E7D32)
    private val Z = Color(0xFFC62828)

    private val XTint = Color(0xFFBBDEFB)
    private val YTint = Color(0xFFC8E6C9)
    private val ZTint = Color(0xFFFFCDD2)

    fun label(family: String): Color = when (family) {
        "x" -> X
        "y" -> Y
        else -> Z
    }

    fun tint(family: String): Color = when (family) {
        "x" -> XTint
        "y" -> YTint
        else -> ZTint
    }
}
