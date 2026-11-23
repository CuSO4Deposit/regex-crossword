package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Fixed key edge so the palette's height does not grow with the font scale. */
private val KeySize = 48.dp
private val KeyGap = 4.dp
internal val StaggeredKeyHeight = 48.dp
private const val DELETE_KEY = '\u232B'

/**
 * On-screen letter keyboard: tap a letter to fill the selected cell.
 *
 * The alphabetical layout keeps the original wrapped palette. Physical
 * keyboard layouts use three centred, staggered rows. Keys are sized in dp
 * (glyph included), unlike the rest of the UI which follows the system font
 * scale, so the palette stays bounded on every device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LetterPalette(
    alphabet: List<Char>,
    onLetter: (Char) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    layout: KeyboardLayout = KeyboardLayout.default,
) {
    val glyphSize = with(LocalDensity.current) { 18.dp.toSp() }
    if (layout.wrapped) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(KeyGap),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            for (letter in alphabet) {
                PaletteKey(letter.toString(), glyphSize, Modifier.size(KeySize)) {
                    onLetter(letter)
                }
            }
            Tip(stringResource(R.string.tooltip_delete)) {
                PaletteKey("\u232B", glyphSize, Modifier.size(KeySize), onClear)
            }
        }
    } else {
        StaggeredPalette(layout, glyphSize, onLetter, onClear, modifier)
    }
}

@Composable
private fun StaggeredPalette(
    layout: KeyboardLayout,
    glyphSize: TextUnit,
    onLetter: (Char) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val columns = layout.rows.maxOf { it.length }.coerceAtLeast(
            layout.rows.last().length + 1,
        )
        val keyWidth = paletteKeyWidth(maxWidth, KeyGap, columns)
        // Material buttons normally reserve a 48dp touch target. At 10 columns
        // that target would overlap the neighbouring key, so use the key box.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                layout.rows.forEachIndexed { index, row ->
                    val keys: List<Char?> = row.map { it } +
                        if (index == layout.rows.lastIndex) listOf(null) else emptyList()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            KeyGap,
                            Alignment.CenterHorizontally,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (key in keys) {
                            if (key == null) {
                                Tip(stringResource(R.string.tooltip_delete)) {
                                    PaletteKey(
                                        DELETE_KEY.toString(),
                                        glyphSize,
                                        Modifier
                                            .width(keyWidth)
                                            .height(StaggeredKeyHeight),
                                        onClear,
                                    )
                                }
                            } else {
                                PaletteKey(
                                    key.toString(),
                                    glyphSize,
                                    Modifier
                                        .width(keyWidth)
                                        .height(StaggeredKeyHeight),
                                ) {
                                    onLetter(key)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Width of one key when [columns] keys and their gaps must fit in [maxWidth]. */
internal fun paletteKeyWidth(maxWidth: Dp, gap: Dp, columns: Int): Dp {
    if (columns <= 0) return 0.dp
    val available = (maxWidth - gap * (columns - 1)).coerceAtLeast(0.dp)
    return (available / columns).coerceAtMost(KeySize)
}

@Composable
private fun PaletteKey(
    label: String,
    glyphSize: TextUnit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text = label, maxLines = 1, fontSize = glyphSize)
    }
}
