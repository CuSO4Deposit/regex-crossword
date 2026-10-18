package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Fixed key edge so the palette's height does not grow with the font scale. */
private val KeySize = 48.dp

/**
 * On-screen letter keyboard: tap a letter to fill the selected cell.
 *
 * The keys are sized in dp (glyph included), unlike the rest of the UI which
 * follows the system font scale. A wrapping palette of full-size Material
 * buttons grew to six-plus rows at large font scales and, because the board
 * is the only weighted child, squeezed the board down to nothing. Pinning the
 * key box keeps the palette bounded on every device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LetterPalette(
    alphabet: List<Char>,
    onLetter: (Char) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glyphSize = with(LocalDensity.current) { 18.dp.toSp() }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (letter in alphabet) {
            PaletteKey(letter.toString(), glyphSize, onClick = { onLetter(letter) })
        }
        Tip(stringResource(R.string.tooltip_delete)) {
            PaletteKey("\u232B", glyphSize, onClick = onClear)
        }
    }
}

@Composable
private fun PaletteKey(label: String, glyphSize: TextUnit, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(KeySize),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text = label, maxLines = 1, fontSize = glyphSize)
    }
}
