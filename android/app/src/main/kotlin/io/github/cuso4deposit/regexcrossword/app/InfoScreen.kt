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
import androidx.compose.ui.res.stringResource
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

@Composable
fun tutorialBlocks(): List<InfoBlock> = listOf(
    InfoBlock.Heading(stringResource(R.string.tut_goal_heading)),
    InfoBlock.Body(stringResource(R.string.tut_goal_body)),
    InfoBlock.Heading(stringResource(R.string.tut_grids_heading)),
    InfoBlock.Item(
        stringResource(R.string.tut_rect_term),
        stringResource(R.string.tut_rect_body),
    ),
    InfoBlock.Item(
        stringResource(R.string.tut_hex_term),
        stringResource(R.string.tut_hex_body),
    ),
    InfoBlock.Item(stringResource(R.string.tut_x_term), stringResource(R.string.tut_x_body)),
    InfoBlock.Item(stringResource(R.string.tut_y_term), stringResource(R.string.tut_y_body)),
    InfoBlock.Item(stringResource(R.string.tut_z_term), stringResource(R.string.tut_z_body)),
    InfoBlock.Heading(stringResource(R.string.tut_controls_heading)),
    InfoBlock.Item(stringResource(R.string.tut_tap_term), stringResource(R.string.tut_tap_body)),
    InfoBlock.Item(stringResource(R.string.tut_hint_term), stringResource(R.string.tut_hint_body)),
    InfoBlock.Item(stringResource(R.string.tut_givens_term), stringResource(R.string.tut_givens_body)),
    InfoBlock.Item(stringResource(R.string.tut_notes_term), stringResource(R.string.tut_notes_body)),
    InfoBlock.Item(stringResource(R.string.tut_clear_term), stringResource(R.string.tut_clear_body)),
    InfoBlock.Item(stringResource(R.string.tut_solve_term), stringResource(R.string.tut_solve_body)),
    InfoBlock.Item(stringResource(R.string.tut_undo_term), stringResource(R.string.tut_undo_body)),
    InfoBlock.Heading(stringResource(R.string.tut_difficulty_heading)),
    InfoBlock.Item(stringResource(R.string.tut_easy_term), stringResource(R.string.tut_easy_body)),
    InfoBlock.Item(stringResource(R.string.tut_medium_term), stringResource(R.string.tut_medium_body)),
    InfoBlock.Item(stringResource(R.string.tut_hard_term), stringResource(R.string.tut_hard_body)),
    InfoBlock.Body(stringResource(R.string.tut_saved_body)),
)

@Composable
fun licenseBlocks(): List<InfoBlock> = listOf(
    InfoBlock.Heading(stringResource(R.string.license_heading)),
    InfoBlock.Body(stringResource(R.string.license_copyright)),
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
    InfoBlock.Heading(stringResource(R.string.credits_heading)),
    InfoBlock.Body(stringResource(R.string.credits_body)),
    InfoBlock.Link(stringResource(R.string.credits_link), ORIGINAL_PUZZLE),
)

@Composable
fun aboutBlocks(): List<InfoBlock> = listOf(
    InfoBlock.Body(stringResource(R.string.about_version, BuildConfig.VERSION_NAME)),
    InfoBlock.Body(stringResource(R.string.about_desc)),
    InfoBlock.Body(stringResource(R.string.about_privacy)),
    InfoBlock.Body(stringResource(R.string.about_built)),
    InfoBlock.Heading(stringResource(R.string.credits_heading)),
    InfoBlock.Body(stringResource(R.string.credits_body)),
    InfoBlock.Link(stringResource(R.string.credits_link), ORIGINAL_PUZZLE),
)
