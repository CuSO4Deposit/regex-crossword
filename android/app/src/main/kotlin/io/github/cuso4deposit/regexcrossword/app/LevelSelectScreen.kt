package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Level select: pick a difficulty, then a level. Levels are infinite, so a
 * "load more" button extends the grid; solved levels get a tick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelSelectScreen(
    store: GameStore,
    version: Int,
    onOpen: (Difficulty, Int) -> Unit,
) {
    var difficulty by remember { mutableStateOf(store.loadPosition()?.difficulty ?: Difficulty.MEDIUM) }
    val solved = remember(version, difficulty) { store.solvedLevels(difficulty) }
    val lastId = remember(version) { store.loadPosition() }
    val lastDifficulty = lastId?.difficulty ?: Difficulty.MEDIUM
    val lastLevel = lastId?.let { levelOf(it.difficulty, it.seed) }?.takeIf { it >= 0 } ?: 0
    var count by remember { mutableStateOf(100) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Regex Crossword") })
                TabRow(selectedTabIndex = difficulty.ordinal) {
                    for (option in Difficulty.entries) {
                        Tab(
                            selected = option == difficulty,
                            onClick = { difficulty = option },
                            text = { Text(option.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(8.dp),
        ) {
            Button(
                onClick = { onOpen(lastDifficulty, lastLevel) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue  \u00B7  ${lastDifficulty.label} Level ${lastLevel + 1}")
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(count) { index ->
                    val levelSolved = solved.contains(index)
                    if (levelSolved) {
                        Button(
                            onClick = { onOpen(difficulty, index) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("${index + 1}  \u2713", textAlign = TextAlign.Center)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onOpen(difficulty, index) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("${index + 1}", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { count += 100 },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load more levels")
            }
        }
    }
}
