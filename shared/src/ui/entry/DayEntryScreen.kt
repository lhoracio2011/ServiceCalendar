package app.servicecalendar.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.servicecalendar.ui.rememberEditorViewModelScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.servicecalendar.data.DayEntryEditorRow
import app.servicecalendar.money.MinorAmount
import app.servicecalendar.money.formatAmount
import app.servicecalendar.money.formatPercent
import app.servicecalendar.ui.catalog.CatalogHeader
import app.servicecalendar.ui.catalog.CenteredText
import app.servicecalendar.ui.catalog.ColorSwatch
import kotlinx.datetime.LocalDate

@Composable
fun DayEntryScreen(date: LocalDate, placeId: Long, onClose: () -> Unit) {
    val editorScope = rememberEditorViewModelScope("day-entry-host")
    val viewModel = viewModel(viewModelStoreOwner = editorScope.owner, key = "day-entry-$date-$placeId") {
        DayEntryViewModel(date, placeId)
    }
    val onClose = {
        editorScope.finish()
        onClose()
    }
    LaunchedEffect(viewModel.closed) { if (viewModel.closed) onClose() }

    if (viewModel.restoredNotice) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoredNotice,
            title = { Text(EntryStrings.RESTORED_TITLE) },
            text = { Text(EntryStrings.RESTORED_TEXT) },
            confirmButton = { TextButton(onClick = viewModel::dismissRestoredNotice) { Text(EntryStrings.RESTORED_OK) } },
        )
    }

    val state = viewModel.state
    if (state == null) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            CatalogHeader(
                title = formatEntryDate(date),
                navigation = { BackButton(onClose) },
            )
            val error = viewModel.loadError
            if (error == null) {
                CenteredText(EntryStrings.LOADING)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = viewModel::reload) { Text(EntryStrings.RETRY) }
                }
            }
        }
    } else {
        DayEntryContent(
            state = state,
            saving = viewModel.saving,
            staleData = viewModel.staleData,
            blocked = viewModel.blocked,
            onSave = viewModel::save,
            onReload = viewModel::reload,
            onClose = onClose,
        )
    }
}

@Composable
fun DayEntryContent(
    state: DayEntryEditorState,
    saving: Boolean,
    staleData: Boolean,
    blocked: Boolean,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (!saving) {
            if (state.isDirty) confirmDiscard = true else onClose()
        }
    }

    val placeTotal = state.placeTotal
    val dayTotal = state.dayTotal

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        CatalogHeader(
            title = state.place.name,
            navigation = { BackButton(requestClose) },
            actions = { ColorSwatch(state.place.color, size = 28.dp, modifier = Modifier.padding(end = 8.dp)) },
        )
        Text(
            formatEntryDate(state.date),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { heading() },
        )
        HorizontalDivider()
        if (blocked) RestoringBanner()

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.rows.isEmpty()) {
                Text(
                    EntryStrings.EDITOR_NO_PROCEDURES,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            ScheduledSection(state, enabled = !blocked)
            state.rows.forEachIndexed { index, row ->
                EntryRow(
                    row = row,
                    quantityText = state.text(row.procedureId),
                    errorText = state.rowError(row.procedureId),
                    lineTotal = state.lineTotal(row.procedureId),
                    imeAction = if (index == state.rows.lastIndex) ImeAction.Done else ImeAction.Next,
                    enabled = !blocked,
                    onQuantityChange = { state.updateQuantity(row.procedureId, it) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TotalLine(
                    label = EntryStrings.PLACE_TOTAL,
                    total = placeTotal,
                    description = EntryStrings.placeTotalDescription(state.place.name, totalText(placeTotal)),
                    emphasized = false,
                )
                TotalLine(
                    label = EntryStrings.DAY_TOTAL,
                    total = dayTotal,
                    description = EntryStrings.dayTotalDescription(totalText(dayTotal)),
                    emphasized = true,
                )
                (state.totalsError ?: state.generalError)?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { error(it) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = requestClose,
                        enabled = !saving,
                        modifier = Modifier.weight(1f).semantics { contentDescription = EntryStrings.CANCEL },
                    ) { Text(EntryStrings.CANCEL) }
                    Button(
                        onClick = onSave,
                        enabled = !saving && !staleData && !blocked && state.isValid && state.rows.isNotEmpty(),
                        modifier = Modifier.weight(1f).semantics { contentDescription = EntryStrings.SAVE },
                    ) { Text(EntryStrings.SAVE) }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(EntryStrings.DISCARD_TITLE) },
            text = { Text(EntryStrings.DISCARD_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(EntryStrings.DISCARD_CONFIRM, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(EntryStrings.DISCARD_KEEP_EDITING) } },
        )
    }

    if (staleData) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(EntryStrings.STALE_TITLE) },
            text = { Text(EntryStrings.STALE_TEXT) },
            confirmButton = { TextButton(onClick = onReload) { Text(EntryStrings.STALE_RELOAD) } },
            dismissButton = { TextButton(onClick = onClose) { Text(EntryStrings.CLOSE) } },
        )
    }
}

@Composable
private fun ScheduledSection(state: DayEntryEditorState, enabled: Boolean) {
    val completed = state.completed.toString()
    val percent = formatPercent(state.percent)
    val problem = state.scheduledError
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.scheduledText,
                onValueChange = state::updateScheduled,
                enabled = enabled,
                singleLine = true,
                label = { Text(EntryStrings.SCHEDULED_LABEL) },
                isError = problem != null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = EntryStrings.scheduledDescription()
                    problem?.let { error(it) }
                },
            )
            Spacer(Modifier.width(16.dp))
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = EntryStrings.summaryDescription(completed, percent)
                },
            ) {
                Text("${EntryStrings.COMPLETED_LABEL}: $completed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${EntryStrings.PERCENT_LABEL}: $percent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (problem != null) {
            Text(
                problem,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RestoringBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            EntryStrings.RESTORING,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun totalText(total: MinorAmount?): String = total?.let { formatAmount(it) } ?: EntryStrings.TOTAL_UNAVAILABLE

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = EntryStrings.CANCEL }) {
        Text(EntryStrings.BACK, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TotalLine(label: String, total: MinorAmount?, description: String, emphasized: Boolean) {
    val style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(totalText(total), style = style)
    }
}

@Composable
private fun EntryRow(
    row: DayEntryEditorRow,
    quantityText: String,
    errorText: String?,
    lineTotal: MinorAmount?,
    imeAction: ImeAction,
    enabled: Boolean,
    onQuantityChange: (String) -> Unit,
) {
    val priceText = formatAmount(row.price)
    val totalText = totalText(lineTotal)
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f).padding(top = 8.dp)
                    .clearAndSetSemantics {
                        contentDescription = EntryStrings.priceDescription(row.procedureName, priceText) +
                            if (row.procedureArchived) ", ${EntryStrings.ARCHIVED_PROCEDURE}" else ""
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(row.procedureName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    priceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.procedureArchived) {
                    Text(
                        EntryStrings.ARCHIVED_PROCEDURE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = quantityText,
                onValueChange = onQuantityChange,
                enabled = enabled,
                singleLine = true,
                placeholder = { Text(EntryStrings.QUANTITY_PLACEHOLDER) },
                isError = errorText != null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
                modifier = Modifier.width(88.dp).semantics {
                    contentDescription = EntryStrings.quantityDescription(row.procedureName)
                    errorText?.let { error(it) }
                },
            )
            Text(
                totalText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(88.dp).padding(start = 8.dp, top = 16.dp)
                    .clearAndSetSemantics { contentDescription = EntryStrings.lineTotalDescription(row.procedureName, totalText) },
            )
        }
        if (errorText != null) {
            Text(
                errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
