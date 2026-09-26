package com.hexregex.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Two screens: level select, then the puzzle. */
@Composable
fun HexregexApp() {
    val context = LocalContext.current
    val store = remember { GameStore(context) }
    var open by remember { mutableStateOf<Pair<Difficulty, Int>?>(null) }
    var version by remember { mutableStateOf(0) }

    val current = open
    if (current == null) {
        LevelSelectScreen(
            store = store,
            version = version,
            onOpen = { difficulty, level -> open = difficulty to level },
        )
    } else {
        BackHandler {
            open = null
            version++
        }
        GameScreen(
            difficulty = current.first,
            level = current.second,
            onBack = {
                open = null
                version++
            },
        )
    }
}
