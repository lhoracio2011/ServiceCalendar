package app.servicecalendar.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.servicecalendar.data.PeriodReport
import app.servicecalendar.data.PlaceReportRow
import app.servicecalendar.data.ProcedureReportRow
import app.servicecalendar.money.formatPercent
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import app.servicecalendar.money.formatAmount
import app.servicecalendar.ui.catalog.ColorSwatch
import app.servicecalendar.ui.theme.ServiceCalendarTheme
import kotlinx.datetime.LocalDate

private enum class PickerTarget { From, To }

@Composable
fun ReportScreen() {
    val viewModel = viewModel { ReportViewModel() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportContent(
        state = state,
        onFromChange = viewModel::setFrom,
        onToChange = viewModel::setTo,
    )
}

@Composable
fun ReportContent(
    state: ReportUiState,
    onFromChange: (LocalDate) -> Unit,
    onToChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picker by rememberSaveable { mutableStateOf<PickerTarget?>(null) }

    Column(modifier.fillMaxSize()) {
        PeriodSelectors(
            from = state.from,
            to = state.to,
            onPickFrom = { picker = PickerTarget.From },
            onPickTo = { picker = PickerTarget.To },
        )
        when (state) {
            is ReportUiState.Loading -> InfoText(ReportStrings.LOADING, null)
            is ReportUiState.Empty -> InfoText(ReportStrings.EMPTY, ReportStrings.EMPTY_HINT)
            is ReportUiState.Error -> ErrorBanner(ReportStrings.errorText(state.reason))
            is ReportUiState.Data -> ReportData(state.report)
        }
    }

    when (picker) {
        PickerTarget.From -> DateDialog(
            title = ReportStrings.PICK_FROM_TITLE,
            initial = state.from,
            onPicked = {
                onFromChange(it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        PickerTarget.To -> DateDialog(
            title = ReportStrings.PICK_TO_TITLE,
            initial = state.to,
            onPicked = {
                onToChange(it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        null -> Unit
    }
}

@Composable
private fun PeriodSelectors(
    from: LocalDate,
    to: LocalDate,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateButton(
            label = ReportStrings.FROM_LABEL,
            date = from,
            description = ReportStrings.fromDescription(from),
            onClick = onPickFrom,
            modifier = Modifier.weight(1f),
        )
        DateButton(
            label = ReportStrings.TO_LABEL,
            date = to,
            description = ReportStrings.toDescription(to),
            onClick = onPickTo,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DateButton(
    label: String,
    date: LocalDate,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.heightIn(min = 64.dp).semantics { contentDescription = description },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ReportStrings.dateText(date), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    title: String,
    initial: LocalDate,
    onPicked: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) onPicked(pickerMillisToDate(millis)) else onDismiss()
                },
            ) { Text(ReportStrings.PICK_CONFIRM) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ReportStrings.PICK_CANCEL) } },
    ) {
        DatePicker(
            state = pickerState,
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

@Composable
private fun ReportData(report: PeriodReport) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = ReportStrings.periodHeading(report.from, report.to),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(report.places, key = { it.place.id }) { row -> PlaceRow(row) }
        }
        TotalBar(report)
    }
}

@Composable
private fun PlaceRow(row: PlaceReportRow) {
    val total = formatAmount(row.total)
    val name = row.place.name + if (row.place.archived) ReportStrings.ARCHIVED_SUFFIX else ""
    val percent = row.percent?.let { formatPercent(it) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .clearAndSetSemantics {
                    contentDescription = ReportStrings.rowDescription(
                        row.place.name,
                        row.place.archived,
                        total,
                        row.entryDays,
                        ReportStrings.statsDescription(row.scheduled, row.completed, percent),
                    ) + row.procedures.joinToString("") {
                        ". " + ReportStrings.procedureDescription(it.name, it.archived, it.quantity, formatAmount(it.total))
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorSwatch(row.place.color, size = 28.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ReportStrings.entryDaysText(row.entryDays),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = total, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 40.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                row.procedures.forEach { procedure ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ReportStrings.procedureLine(procedure.name, procedure.archived, procedure.quantity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Text(
                            text = formatAmount(procedure.total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = ReportStrings.statsLine(row.scheduled, row.completed, formatPercent(row.percent)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TotalBar(report: PeriodReport) {
    val text = formatAmount(report.total)
    val percent = report.percent?.let { formatPercent(it) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clearAndSetSemantics {
                    contentDescription = ReportStrings.totalDescription(
                        text,
                        ReportStrings.statsDescription(report.scheduled, report.completed, percent),
                    )
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ReportStrings.TOTAL, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                text = ReportStrings.statsLine(report.scheduled, report.completed, formatPercent(report.percent)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun InfoText(title: String, hint: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val previewFrom = LocalDate(2026, 11, 1)
private val previewTo = LocalDate(2026, 11, 2)
private val previewPlaceA = Place(1, "West Clinic", null, 0xFF22B14CL)
private val previewPlaceB = Place(2, "East Center", null, 0xFFFF7F27L, archived = true)

@Preview
@Composable
private fun ReportDataPreview() = ServiceCalendarTheme {
    ReportContent(
        state = ReportUiState.Data(
            PeriodReport(
                previewFrom,
                previewTo,
                listOf(
                    PlaceReportRow(
                        previewPlaceA, MinorAmount(5000), 2,
                        listOf(ProcedureReportRow(1, "Checkup", false, 4, MinorAmount(5000))),
                        scheduled = 110, completed = 67, percent = 60.9,
                    ),
                    PlaceReportRow(previewPlaceB, MinorAmount(4000), 1),
                ),
                MinorAmount(9000),
                scheduled = 110,
                completed = 71,
                percent = 64.5,
            ),
        ),
        onFromChange = {},
        onToChange = {},
    )
}

@Preview
@Composable
private fun ReportEmptyPreview() = ServiceCalendarTheme {
    ReportContent(ReportUiState.Empty(previewFrom, previewTo), {}, {})
}

@Preview
@Composable
private fun ReportErrorPreview() = ServiceCalendarTheme {
    ReportContent(ReportUiState.Error(previewTo, previewFrom, ReportError.InvalidPeriod), {}, {})
}
