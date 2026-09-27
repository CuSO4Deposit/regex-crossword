package io.github.cuso4deposit.regexcrossword.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

private sealed interface Screen {
    data object Home : Screen
    data object Tutorial : Screen
    data object License : Screen
    data object About : Screen
    data class Levels(val difficulty: Difficulty) : Screen
    data class Game(val id: PuzzleId) : Screen
}

/** Home -> levels -> puzzle, plus the How-to-play / License / About pages. */
@Composable
fun HexregexApp() {
    val context = LocalContext.current
    val store = remember { GameStore(context) }
    var version by remember { mutableStateOf(0) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    fun goHome() {
        screen = Screen.Home
        version++
    }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            onPlay = { difficulty -> screen = Screen.Levels(difficulty) },
            onTutorial = { screen = Screen.Tutorial },
            onLicense = { screen = Screen.License },
            onAbout = { screen = Screen.About },
        )

        Screen.Tutorial ->
            InfoScreen(stringResource(R.string.title_how_to_play), tutorialBlocks(), onBack = { goHome() })
        Screen.License ->
            InfoScreen(stringResource(R.string.title_license), licenseBlocks(), onBack = { goHome() })
        Screen.About ->
            InfoScreen(stringResource(R.string.title_about), aboutBlocks(), onBack = { goHome() })

        is Screen.Levels -> {
            BackHandler { goHome() }
            LevelSelectScreen(
                store = store,
                version = version,
                initialDifficulty = current.difficulty,
                onBack = { goHome() },
                onOpen = { difficulty, level ->
                    screen = Screen.Game(puzzleIdFor(difficulty, level))
                },
            )
        }

        is Screen.Game -> {
            fun backToLevels() {
                screen = Screen.Levels(current.id.difficulty)
                version++
            }
            BackHandler { backToLevels() }
            GameScreen(
                id = current.id,
                onBack = { backToLevels() },
                onNextLevel = {
                    val level = levelOf(current.id.difficulty, current.id.seed).coerceAtLeast(0)
                    screen = Screen.Game(
                        PuzzleId(
                            current.id.version,
                            current.id.difficulty,
                            seedFor(current.id.difficulty, level + 1),
                        ),
                    )
                },
            )
        }
    }
}
