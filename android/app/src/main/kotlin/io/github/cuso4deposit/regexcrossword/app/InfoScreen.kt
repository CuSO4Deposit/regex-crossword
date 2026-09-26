package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(title: String, body: String, onBack: () -> Unit) {
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
                .padding(16.dp),
        ) {
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

const val TUTORIAL_TEXT: String = """
How to play
===========

Every line of the grid is a regular expression. Fill the cells so that each
line, read in its direction, matches its clue exactly (a whole-string match).

Rectangle (Easy)
  Each row reads left to right; each column reads top to bottom.

Hexagon (Medium / Hard)
  Three directions, colour-coded and shown next to each clue:
    X  blue   read bottom to top  (up arrow)
    Y  green  read left to right  (right arrow)
    Z  red    read top to bottom  (down arrow)
  The cell ringed in a family's colour is where that line starts reading.

Controls
  Tap a cell, then tap a letter to fill it. Use the on-screen keyboard; the
  backspace clears the selected cell. Filling a line completely and wrongly
  outlines it in red automatically; finishing the whole grid shows a
  "Solved!" dialog.

  Hint    reveal one cell from a completion consistent with what you have.
  Givens  fill every cell that a single clue already pins down.
  Notes   toggle pencil marks: tap letters to add/remove candidates.
  Clear   erase the grid (asks first).
  Solve   (menu, top right) fill one valid solution after confirming.

Difficulty
  Easy    rectangle.
  Medium  hexagon, loose clues (several solutions).
  Hard    hexagon with a unique solution, generated with the solver.

Progress is saved automatically; each level keeps its own grid and notes.
"""

const val LICENSE_TEXT: String = """
License
=======

MIT License

Copyright (c) 2026 cuso4deposit

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

This app is a port of "hexregex", a solver and generator for regular
crosswords (also MIT). The hexagonal "A Regular Crossword" form was
popularised by Dan Gulotta's puzzle in MIT Mystery Hunt 2013.
"""

const val ABOUT_TEXT: String = """
About
=====

Regex Crossword 0.1.0

A phone port of the hexregex solver + generator. Levels are generated on
device from a seed; hard levels are pre-generated with the solver to
guarantee a unique solution.

No accounts, no ads, no network access, no permissions.

Built with Kotlin and Jetpack Compose.
"""
