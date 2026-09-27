package io.github.cuso4deposit.regexcrossword.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** A small structured-block vocabulary so the info pages can be formatted. */
sealed interface InfoBlock {
    data class Heading(val text: String) : InfoBlock
    data class Body(val text: String) : InfoBlock
    data class Item(val term: String, val text: String) : InfoBlock
    data class Link(val label: String, val url: String) : InfoBlock
}

private val ORIGINAL_PUZZLE =
    "https://puzzles.mit.edu/2013/coinheist.com/rubik/a_regular_crossword/"

private const val CREDIT =
    "This app is an independent implementation. The hexagonal \u201CA Regular " +
        "Crossword\u201D form was popularised by Dan Gulotta (based on an idea by " +
        "Palmer Mebane) in MIT Mystery Hunt 2013. This app includes neither the " +
        "original puzzle nor its text."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(title: String, blocks: List<InfoBlock>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            for (block in blocks) {
                InfoBlockView(block)
            }
        }
    }
}

@Composable
private fun InfoBlockView(block: InfoBlock) {
    when (block) {
        is InfoBlock.Heading -> Text(
            text = block.text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )

        is InfoBlock.Body -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        is InfoBlock.Item -> Row(modifier = Modifier.padding(bottom = 10.dp)) {
            Text(
                text = "\u2022",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column {
                Text(block.term, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is InfoBlock.Link -> {
            val uriHandler = LocalUriHandler.current
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    text = block.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(block.url) },
                )
                Text(
                    text = block.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { uriHandler.openUri(block.url) },
                )
            }
        }
    }
}

val TUTORIAL_BLOCKS: List<InfoBlock> = listOf(
    InfoBlock.Heading("The goal"),
    InfoBlock.Body(
        "Every line of the grid is a regular expression. Fill the cells so that " +
            "each line, read in its own direction, matches its clue exactly \u2014 a " +
            "whole-string match.",
    ),
    InfoBlock.Heading("Grids"),
    InfoBlock.Item(
        "Rectangle (Easy)",
        "each row reads left to right; each column reads top to bottom.",
    ),
    InfoBlock.Item(
        "Hexagon (Medium / Hard)",
        "three colour-coded directions. The arrow beside a clue shows where it " +
            "reads from; the cell ringed in that colour is the first cell of the line.",
    ),
    InfoBlock.Item("X \u00B7 blue", "read bottom to top (up arrow)."),
    InfoBlock.Item("Y \u00B7 green", "read left to right (right arrow)."),
    InfoBlock.Item("Z \u00B7 red", "read top to bottom (down arrow)."),
    InfoBlock.Heading("Controls"),
    InfoBlock.Item("Tap a cell", "then tap a letter to fill it; backspace clears it."),
    InfoBlock.Item(
        "Hint",
        "reveals one cell from a completion consistent with what you have entered.",
    ),
    InfoBlock.Item(
        "Givens",
        "fills cells whose letter a clue literally writes down at a fixed position " +
            "(no search).",
    ),
    InfoBlock.Item("Notes", "toggles pencil marks: tap letters to add or remove candidates."),
    InfoBlock.Item("Clear", "erases the grid, after asking."),
    InfoBlock.Item("Solve", "menu (top right) \u2014 fills one valid solution after confirming."),
    InfoBlock.Item("Undo / Redo", "top bar \u21B6 \u21B7 arrows \u2014 step through your edits."),
    InfoBlock.Heading("Difficulty"),
    InfoBlock.Item("Easy", "rectangle, 5\u00D75."),
    InfoBlock.Item("Medium", "hexagon with loose clues \u2014 several valid solutions."),
    InfoBlock.Item("Hard", "hexagon with a unique solution, pre-generated with the solver."),
    InfoBlock.Body("Progress and the timer are saved automatically for every level."),
)

val LICENSE_BLOCKS: List<InfoBlock> = listOf(
    InfoBlock.Heading("MIT License"),
    InfoBlock.Body("Copyright (c) 2026 cuso4deposit"),
    InfoBlock.Body(
        """
        Permission is hereby granted, free of charge, to any person obtaining a
        copy of this software and associated documentation files (the
        "Software"), to deal in the Software without restriction, including
        without limitation the rights to use, copy, modify, merge, publish,
        distribute, sublicense, and/or sell copies of the Software, and to permit
        persons to whom the Software is furnished to do so, subject to the
        following conditions:
        """.trimIndent(),
    ),
    InfoBlock.Body(
        "The above copyright notice and this permission notice shall be included " +
            "in all copies or substantial portions of the Software.",
    ),
    InfoBlock.Body(
        """
        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
        OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
        MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
        NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
        DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
        OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
        USE OR OTHER DEALINGS IN THE SOFTWARE.
        """.trimIndent(),
    ),
    InfoBlock.Heading("Credits"),
    InfoBlock.Body(CREDIT),
    InfoBlock.Link("Original puzzle \u2014 A Regular Crossword (MIT Mystery Hunt 2013)", ORIGINAL_PUZZLE),
)

val ABOUT_BLOCKS: List<InfoBlock> = listOf(
    InfoBlock.Body("Regex Crossword 0.1.0"),
    InfoBlock.Body(
        "An offline regex-crossword app. Levels are generated on device from a " +
            "seed; hard levels are pre-generated with the solver to guarantee a " +
            "unique solution.",
    ),
    InfoBlock.Body("No accounts, no ads, no network access, no permissions."),
    InfoBlock.Body("Built with Kotlin and Jetpack Compose."),
    InfoBlock.Heading("Credits"),
    InfoBlock.Body(CREDIT),
    InfoBlock.Link("Original puzzle \u2014 A Regular Crossword (MIT Mystery Hunt 2013)", ORIGINAL_PUZZLE),
)
