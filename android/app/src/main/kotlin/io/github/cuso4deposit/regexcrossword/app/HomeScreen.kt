package io.github.cuso4deposit.regexcrossword.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPlay: (Difficulty) -> Unit,
    onTutorial: () -> Unit,
    onLicense: () -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Regex Crossword") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Fill every line so it matches its regular expression.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            for (difficulty in Difficulty.entries) {
                Button(
                    onClick = { onPlay(difficulty) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(difficulty.label) }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onTutorial, modifier = Modifier.fillMaxWidth()) {
                Text("How to play")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onLicense, modifier = Modifier.fillMaxWidth()) {
                Text("License")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
                Text("About")
            }
        }
    }
}
