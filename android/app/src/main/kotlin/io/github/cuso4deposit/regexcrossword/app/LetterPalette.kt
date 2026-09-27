package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** On-screen letter keyboard: tap a letter to fill the selected cell. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LetterPalette(
    alphabet: List<Char>,
    onLetter: (Char) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (letter in alphabet) {
            OutlinedButton(onClick = { onLetter(letter) }) {
                Text(letter.toString())
            }
        }
        Tip("Delete \u2014 clear the selected cell") {
            OutlinedButton(onClick = onClear) {
                Text("\u232B")
            }
        }
    }
}
