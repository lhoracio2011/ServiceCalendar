package app.servicecalendar.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.servicecalendar.backup.BackupPhase
import app.servicecalendar.backup.BackupUiState
import app.servicecalendar.backup.RestoreReport

@Composable
fun BackupScreen() {
    val viewModel = viewModel { BackupViewModel() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val model = viewModel.model
    BackupContent(
        state = state,
        onConnect = model::connect,
        onBackUpNow = model::backUpNow,
        onDisconnect = model::requestDisconnect,
        onConfirmDisconnect = model::confirmDisconnect,
        onCancelDisconnect = model::cancelDisconnect,
        onRetry = model::retry,
        onPostpone = model::postpone,
        onChooseRestore = model::chooseRestore,
        onChooseReplace = model::chooseReplace,
        onDismissChoice = model::dismissChoice,
        onConfirmReplace = model::confirmReplace,
        onCancelReplace = model::cancelReplace,
        onConfirmRestore = model::confirmRestore,
        onCancelRestore = model::cancelRestore,
        onDismissNotice = model::dismissNotice,
    )
}

@Composable
fun BackupContent(
    state: BackupScreenState,
    onConnect: () -> Unit,
    onBackUpNow: () -> Unit,
    onDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onCancelDisconnect: () -> Unit,
    onRetry: (BackupRetry) -> Unit,
    onPostpone: () -> Unit,
    onChooseRestore: () -> Unit,
    onChooseReplace: () -> Unit,
    onDismissChoice: () -> Unit,
    onConfirmReplace: () -> Unit,
    onCancelReplace: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.available) {
                Unavailable()
            } else {
                Body(
                    state = state,
                    onConnect = onConnect,
                    onBackUpNow = onBackUpNow,
                    onDisconnect = onDisconnect,
                    onRetry = onRetry,
                    onPostpone = onPostpone,
                    onChooseRestore = onChooseRestore,
                    onChooseReplace = onChooseReplace,
                    onDismissNotice = onDismissNotice,
                )
            }
        }
    }

    when (val dialog = state.dialog) {
        BackupDialog.Choice -> ChoiceDialog(
            onRestore = onChooseRestore,
            onReplace = onChooseReplace,
            onPostpone = onPostpone,
            onDismiss = onDismissChoice,
        )

        is BackupDialog.ConfirmReplace -> ReplaceDialog(onConfirm = onConfirmReplace, onCancel = onCancelReplace)
        is BackupDialog.ConfirmRestore -> RestoreDialog(report = dialog.report, onConfirm = onConfirmRestore, onCancel = onCancelRestore)
        BackupDialog.ConfirmDisconnect -> DisconnectDialog(onConfirm = onConfirmDisconnect, onCancel = onCancelDisconnect)
        null -> Unit
    }
}

@Composable
private fun Unavailable() {
    Card {
        Text(BackupStrings.UNAVAILABLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            BackupStrings.UNAVAILABLE_HINT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Body(
    state: BackupScreenState,
    onConnect: () -> Unit,
    onBackUpNow: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: (BackupRetry) -> Unit,
    onPostpone: () -> Unit,
    onChooseRestore: () -> Unit,
    onChooseReplace: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val backup = state.backup
    val idle = !state.blocked

    ProgressCard(state)
    state.notice?.let { Notice(it, onDismissNotice) }
    state.errorText?.let { text ->
        ErrorBanner(text, retry = state.retry, enabled = idle, onRetry = onRetry)
    }

    if (backup.phase == BackupPhase.NOT_CONNECTED) {
        Card {
            Text(BackupStrings.NOT_CONNECTED, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                BackupStrings.NOT_CONNECTED_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConnect, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(BackupStrings.CONNECT)
            }
        }
        return
    }

    Card {
        Text(BackupStrings.ACCOUNT_LABEL, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(backup.accountLabel.orEmpty(), style = MaterialTheme.typography.titleMedium)
        StatusBlock(backup)
        state.searchRetry?.let { retry ->
            OutlinedButton(onClick = { onRetry(retry) }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(BackupStrings.RETRY_SEARCH)
            }
        }
        Text(BackupStrings.LAST_BACKUP_LABEL, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(BackupStrings.lastBackupText(backup.lastSuccessfulBackupAt), style = MaterialTheme.typography.bodyLarge)
        if (backup.phase == BackupPhase.ACTIVE) {
            Button(onClick = onBackUpNow, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(BackupStrings.BACK_UP_NOW)
            }
        }
        OutlinedButton(onClick = onDisconnect, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(BackupStrings.DISCONNECT)
        }
    }

    if (backup.phase == BackupPhase.AWAITING_CHOICE) {
        Card {
            Text(BackupStrings.CHOICE_TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(BackupStrings.CHOICE_TEXT, style = MaterialTheme.typography.bodyMedium)
            if (backup.choicePostponed) {
                Text(BackupStrings.CHOICE_POSTPONED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ChoiceButtons(
                enabled = idle,
                showPostpone = !backup.choicePostponed,
                onRestore = onChooseRestore,
                onReplace = onChooseReplace,
                onPostpone = onPostpone,
            )
        }
    }
}

@Composable
private fun StatusBlock(backup: BackupUiState) {
    val (title, hint) = when (backup.phase) {
        BackupPhase.ACTIVE -> BackupStrings.STATUS_ACTIVE to BackupStrings.STATUS_ACTIVE_HINT
        BackupPhase.SEARCH_REQUIRED -> BackupStrings.STATUS_SEARCH to BackupStrings.STATUS_SEARCH_HINT
        BackupPhase.SEARCH_FAILED -> BackupStrings.STATUS_SEARCH_FAILED to BackupStrings.STATUS_SEARCH_FAILED_HINT
        BackupPhase.AWAITING_CHOICE -> BackupStrings.CHOICE_TITLE to BackupStrings.CHOICE_TEXT
        BackupPhase.NOT_CONNECTED -> BackupStrings.NOT_CONNECTED to ""
    }
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    if (hint.isNotEmpty() && backup.phase != BackupPhase.AWAITING_CHOICE) {
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceButtons(
    enabled: Boolean,
    showPostpone: Boolean,
    onRestore: () -> Unit,
    onReplace: () -> Unit,
    onPostpone: () -> Unit,
) {
    Button(onClick = onRestore, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(BackupStrings.RESTORE_COPY)
    }
    OutlinedButton(onClick = onReplace, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(BackupStrings.REPLACE_COPY, textAlign = TextAlign.Center)
    }
    if (showPostpone) {
        TextButton(onClick = onPostpone, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(BackupStrings.DECIDE_LATER)
        }
    }
}

@Composable
private fun ProgressCard(state: BackupScreenState) {
    val operation = state.backup.busy
    if (operation == null && !state.working) return
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    text = operation?.let(BackupStrings::operationText) ?: BackupStrings.CHECKING,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (state.restoring) {
                    Text(BackupStrings.RESTORING_HINT, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Notice(notice: BackupNotice, onDismiss: () -> Unit) {
    val text = when (notice) {
        BackupNotice.Restored -> BackupStrings.NOTICE_RESTORED
        BackupNotice.RestoreStale -> BackupStrings.NOTICE_STALE
        BackupNotice.ReplaceUnavailable -> BackupStrings.NOTICE_REPLACE_UNAVAILABLE
        BackupNotice.BackupSaved -> BackupStrings.NOTICE_BACKUP_SAVED
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onDismiss) { Text(BackupStrings.DISMISS) }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, retry: BackupRetry?, enabled: Boolean, onRetry: (BackupRetry) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (retry != null) {
                val label = when (retry) {
                    BackupRetry.Reconnect -> BackupStrings.RESTORE_ACCESS
                    BackupRetry.PrepareRestore -> BackupStrings.RETRY_RESTORE
                    BackupRetry.Sync -> BackupStrings.RETRY
                }
                Button(onClick = { onRetry(retry) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ChoiceDialog(onRestore: () -> Unit, onReplace: () -> Unit, onPostpone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(BackupStrings.CHOICE_TITLE) },
        text = { Text(BackupStrings.CHOICE_TEXT) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRestore) { Text(BackupStrings.RESTORE_COPY) }
                TextButton(onClick = onReplace) { Text(BackupStrings.REPLACE_COPY) }
                TextButton(onClick = onPostpone) { Text(BackupStrings.DECIDE_LATER) }
            }
        },
    )
}

@Composable
private fun ReplaceDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(BackupStrings.REPLACE_TITLE) },
        text = { Text(BackupStrings.REPLACE_TEXT) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(BackupStrings.REPLACE_CONFIRM, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(BackupStrings.CANCEL) } },
    )
}

@Composable
private fun DisconnectDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(BackupStrings.DISCONNECT_TITLE) },
        text = { Text(BackupStrings.DISCONNECT_TEXT) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(BackupStrings.DISCONNECT_CONFIRM) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(BackupStrings.CANCEL) } },
    )
}

@Composable
private fun RestoreDialog(report: RestoreReport, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(BackupStrings.RESTORE_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(BackupStrings.copyCreatedLine(report.createdAt))
                Text(BackupStrings.restoreCopyLine(report))
                Text(BackupStrings.restoreLocalLine(report))
                Text(BackupStrings.RESTORE_WARNING, fontWeight = FontWeight.Bold)
                if (!report.differsFromLocal) Text(BackupStrings.RESTORE_SAME)
                if (report.currencyCode != report.localCurrencyCode) Text(BackupStrings.RESTORE_CURRENCY_DIFFERS)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(BackupStrings.RESTORE_CONFIRM, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(BackupStrings.CANCEL) } },
    )
}
