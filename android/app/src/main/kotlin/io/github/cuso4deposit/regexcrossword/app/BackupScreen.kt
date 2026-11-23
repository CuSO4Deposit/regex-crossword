package io.github.cuso4deposit.regexcrossword.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class RestorePreview(
    val decoded: DecodedBackup,
    val local: BackupPayload?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { BackupStorage(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RestorePreview?>(null) }

    val exportSuccess = stringResource(R.string.backup_export_success)
    val genericFailure = stringResource(R.string.backup_failed_generic)

    suspend fun showError(error: Throwable) {
        snackbarHostState.showSnackbar(
            message = context.getString(
                R.string.backup_failed,
                error.message ?: genericFailure,
            ),
            duration = SnackbarDuration.Long,
        )
    }

    fun exportTo(uri: Uri) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val encoded = BackupCodec.encode(storage.snapshot())
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw BackupFormatException(genericFailure)
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(encoded.text) }
                }
                snackbarHostState.showSnackbar(exportSuccess)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showError(error)
            } finally {
                busy = false
            }
        }
    }

    fun readFrom(uri: Uri) {
        scope.launch {
            busy = true
            try {
                pending = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: throw BackupFormatException(genericFailure)
                    val decoded = BackupCodec.decode(BackupCodec.decodeUtf8(bytes))
                    val local = try {
                        storage.snapshot()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                    RestorePreview(decoded, local)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pending = null
                showError(error)
            } finally {
                busy = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) readFrom(uri)
    }

    BackHandler(enabled = busy) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !busy) {
                        Text("\u2190", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                text = stringResource(R.string.backup_intro),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { exportLauncher.launch(backupFileName()) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_export))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/json",
                            "text/plain",
                            "application/octet-stream",
                        ),
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_restore))
            }
            if (busy) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.backup_working),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.backup_replace_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    pending?.let { preview ->
        val payload = preview.decoded.payload
        val local = preview.local
        val completed = payload.progress.count { it.completed }
        val oldVersions = payload.progress.count { it.puzzle.version != GENERATOR_VERSION }
        val comparison = local?.let { BackupPreview.compare(payload, it) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                ) {
                    item {
                        DifficultyBreakdown(
                            title = stringResource(R.string.backup_contents_title),
                            payload = payload,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    if (local != null) {
                        item {
                            DifficultyBreakdown(
                                title = stringResource(R.string.backup_local_title),
                                payload = local,
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                        comparison?.let {
                            if (it.conflicts > 0) {
                                item {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.backup_conflict_warning,
                                            it.conflicts,
                                            it.conflicts,
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                items(
                                    items = it.conflictingPuzzles,
                                    key = { id ->
                                        "${id.version}_${id.difficulty.name}_${id.seed}"
                                    },
                                ) { id ->
                                    ConflictRow(id)
                                }
                                item { Spacer(Modifier.height(6.dp)) }
                            }
                            if (it.added > 0) {
                                item {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.backup_added_count,
                                            it.added,
                                            it.added,
                                        ),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            if (it.localOnly > 0) {
                                item {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.backup_local_only_count,
                                            it.localOnly,
                                            it.localOnly,
                                        ),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            if (it.matchesLocal) {
                                item {
                                    Text(stringResource(R.string.backup_matches_local))
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = stringResource(R.string.backup_compare_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    item {
                        Text(
                            text = pluralStringResource(
                                R.plurals.backup_progress_count,
                                payload.progress.size,
                                payload.progress.size,
                            ),
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.backup_completed_count,
                                completed,
                                completed,
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.backup_confirm_replace))
                    }
                    if (oldVersions > 0) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                pluralStringResource(
                                    R.plurals.backup_old_versions,
                                    oldVersions,
                                    oldVersions,
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        scope.launch {
                            busy = true
                            try {
                                if (withContext(Dispatchers.IO) { storage.restore(payload) }) {
                                    onRestored()
                                } else {
                                    showError(
                                        BackupFormatException(
                                            context.getString(R.string.backup_restore_commit_failed),
                                        ),
                                    )
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                showError(error)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.backup_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConflictRow(id: PuzzleId) {
    val difficulty = stringResource(id.difficulty.labelRes)
    val level = levelOf(id.difficulty, id.seed) + 1
    val identity =
        if (level > 0) {
            stringResource(
                R.string.backup_conflict_identity,
                difficulty,
                level,
                id.version,
                id.seed,
            )
        } else {
            stringResource(
                R.string.backup_conflict_identity_no_level,
                difficulty,
                id.version,
                id.seed,
            )
        }
    Text(
        text = stringResource(R.string.backup_conflict_item, identity),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun DifficultyBreakdown(title: String, payload: BackupPayload) {
    val counts = remember(payload) { BackupPreview.counts(payload) }
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    for (difficulty in Difficulty.entries) {
        val summary = counts.getValue(difficulty)
        Text(
            text = stringResource(
                R.string.backup_difficulty_counts,
                stringResource(difficulty.labelRes),
                summary.solved,
                summary.inProgress,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun readLimited(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > BACKUP_MAX_BYTES) {
            throw BackupFormatException("backup exceeds the 32 MiB limit")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun backupFileName(): String {
    val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return "regex-crossword-backup-${format.format(Date())}.json"
}
