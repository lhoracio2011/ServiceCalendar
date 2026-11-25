package app.servicecalendar.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.servicecalendar.money.formatPercent
import app.servicecalendar.ui.entry.EntryStrings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servicecalendar.ui.theme.AppIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.servicecalendar.data.DayCardLine
import app.servicecalendar.data.DayMark
import app.servicecalendar.data.DaySummary
import app.servicecalendar.data.PlaceDayCard
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import app.servicecalendar.money.formatAmount
import app.servicecalendar.ui.theme.ServiceCalendarTheme
import app.servicecalendar.ui.theme.toComposeColor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

@Composable
fun CalendarScreen(
    onAddEntry: (date: LocalDate) -> Unit,
    onOpenEntry: (date: LocalDate, placeId: Long) -> Unit,
) {
    val viewModel = viewModel { CalendarViewModel() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CalendarContent(
        state = state,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onToday = viewModel::goToToday,
        onSelectDate = viewModel::select,
        onAddEntry = onAddEntry,
        onOpenEntry = onOpenEntry,
        onEditScheduled = { placeId, current -> viewModel.scheduledEdit.begin(state.selected, placeId, current) },
    )
    ScheduledDialogHost(viewModel.scheduledEdit)
}

@Composable
fun CalendarContent(
    state: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onAddEntry: (LocalDate) -> Unit,
    onOpenEntry: (LocalDate, Long) -> Unit,
    onEditScheduled: (placeId: Long, current: Int?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MonthHeader(state, onPreviousMonth, onNextMonth, onToday)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item(key = "weekdays") { WeekdayHeader() }
                item(key = "grid") { MonthGridView(state, onSelectDate) }
                val summary = state.summary
                if (summary != null) {
                    items(summary.places, key = { it.entryId }) { card ->
                        PlaceCard(
                            card = card,
                            date = state.selected,
                            onClick = { onOpenEntry(state.selected, card.place.id) },
                            onEditScheduled = { onEditScheduled(card.place.id, card.scheduled) },
                        )
                    }
                }
                item(key = "fab-space") { Spacer(Modifier.height(88.dp)) }
            }
        }
        FloatingActionButton(
            onClick = { onAddEntry(state.selected) },
            shape = CircleShape,
            containerColor = calendarCardColor(),
            contentColor = MaterialTheme.colorScheme.secondary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp)
                .semantics { contentDescription = CalendarStrings.ADD },
        ) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun MonthHeader(
    state: CalendarUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            IconButton(
                onClick = onPreviousMonth,
                enabled = state.canGoPrevious,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            ) {
                Icon(
                    imageVector = AppIcons.ArrowBack,
                    contentDescription = CalendarStrings.PREVIOUS_MONTH,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = CalendarStrings.monthTitle(state.month).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.45.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).offset(x = (-24).dp).semantics {
                    heading()
                    contentDescription = CalendarStrings.monthTitle(state.month)
                },
            )
            IconButton(
                onClick = onToday,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 92.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Today,
                    contentDescription = CalendarStrings.TODAY_DESCRIPTION,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = onNextMonth,
                enabled = state.canGoNext,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            ) {
                Icon(
                    imageVector = AppIcons.ArrowForward,
                    contentDescription = CalendarStrings.NEXT_MONTH,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    Surface(
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp), verticalAlignment = Alignment.CenterVertically) {
            weekdayOrder().forEach { day ->
                Text(
                    text = CalendarStrings.weekdayShort(day),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = CalendarStrings.weekdayFull(day) },
                )
            }
        }
    }
}

@Composable
private fun MonthGridView(state: CalendarUiState, onSelectDate: (LocalDate) -> Unit) {
    val grid = buildMonthGrid(state.month, minWeeks = 6)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.5.dp, bottom = 13.dp)) {
        grid.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(
                        cell = cell,
                        marks = if (cell.inMonth) state.marks[cell.date].orEmpty() else emptyList(),
                        isSelected = cell.date == state.selected,
                        isToday = cell.date == state.today,
                        onSelect = { onSelectDate(cell.date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private const val MAX_DOTS = 4

private val TodayNumberColor = Color(0xFF388E3C)

@Composable
private fun calendarInk(): Color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.Black

@Composable
private fun calendarCardColor(): Color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else Color.White

@Composable
private fun DayCell(
    cell: GridCell,
    marks: List<DayMark>,
    isSelected: Boolean,
    isToday: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = marks.map { it.placeName }
    val description = CalendarStrings.dayCellDescription(
        date = cell.date,
        recordCount = if (cell.inMonth) marks.size else null,
        placeNames = names,
        isToday = isToday,
        isSelected = isSelected,
    )
    val colors = MaterialTheme.colorScheme
    val ink = calendarInk()
    val numberColor = when {
        isSelected -> if (isSystemInDarkTheme()) colors.onSecondaryContainer else Color.Black
        isToday -> if (isSystemInDarkTheme()) colors.secondary else TodayNumberColor
        cell.inMonth -> ink
        else -> ink.copy(alpha = 0.22f)
    }
    Box(
        modifier = modifier
            .height(50.5.dp)
            .clickable(role = Role.Button, onClick = onSelect)
            .clearAndSetSemantics {
                contentDescription = description
                selected = isSelected
                role = Role.Button
                onClick(label = null) { onSelect(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .then(if (isSelected) Modifier.background(colors.secondaryContainer, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = cell.date.day.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = numberColor,
                maxLines = 1,
                softWrap = false,
            )
        }
        Row(
            modifier = Modifier
                .offset(y = 20.dp)
                .semantics { if (names.isNotEmpty()) contentDescription = CalendarStrings.marksDescription(names) },
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            marks.take(MAX_DOTS).forEach { mark ->
                Box(Modifier.size(6.dp).background(mark.color.toComposeColor(), CircleShape))
            }
        }
    }
}

@Composable
private fun PlaceCard(card: PlaceDayCard, date: LocalDate, onClick: () -> Unit, onEditScheduled: () -> Unit) {
    val name = card.place.name + if (card.place.archived) CalendarStrings.ARCHIVED_SUFFIX else ""
    val total = formatAmount(card.placeTotal)
    val ink = calendarInk()
    val scheduledText = card.scheduled?.toString()
    val completedText = card.completed.toString()
    val percentText = formatPercent(card.percent)
    // The card itself is not one clickable node: that would swallow the semantics of the A box. The date area and the body
    // are separate tap targets, and only the body is announced.
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = calendarCardColor(),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.heightIn(min = 91.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(card.place.color.toComposeColor()))
            Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clickable(onClick = onClick)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = CalendarStrings.cardDate(date),
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 16.sp,
                            color = ink,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    StatBox(
                        label = CalendarStrings.LABEL_SCHEDULED,
                        value = scheduledText.orEmpty(),
                        description = CalendarStrings.scheduledDescription(scheduledText),
                        onClick = onEditScheduled,
                    )
                    StatBox(
                        label = CalendarStrings.LABEL_COMPLETED,
                        value = completedText,
                        description = CalendarStrings.completedDescription(completedText),
                    )
                    StatBox(
                        label = CalendarStrings.LABEL_PERCENT,
                        value = percentText,
                        description = CalendarStrings.percentDescription(
                            if (card.percent == null) CalendarStrings.NOT_AVAILABLE else percentText,
                        ),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .clearAndSetSemantics { contentDescription = CalendarStrings.cardDescription(name, total) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Text(
                            text = total,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = ink,
                        )
                    }
                    card.lines.forEach { line ->
                        Text(
                            text = CalendarStrings.lineText(line.procedureName, line.quantity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A small labelled value: "A [110]". With [onClick] the box is a button; without it the box is read-only. */
@Composable
private fun StatBox(label: String, value: String, description: String, onClick: (() -> Unit)? = null) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .padding(start = 3.dp)
                .width(44.dp)
                .height(30.dp)
                .border(1.dp, outline.copy(alpha = if (onClick != null) 0.9f else 0.45f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = calendarInk(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun ScheduledDialogHost(model: ScheduledEditModel) {
    val edit = model.editing
    if (edit != null) {
        ScheduledDialog(
            edit = edit,
            error = model.error,
            saving = model.saving,
            onTextChange = model::clearError,
            onSave = model::save,
            onCancel = model::cancel,
        )
    }
    if (model.staleNotice) {
        AlertDialog(
            onDismissRequest = model::dismissStaleNotice,
            title = { Text(EntryStrings.STALE_TITLE) },
            text = { Text(CalendarStrings.SCHEDULED_STALE_TEXT) },
            confirmButton = { TextButton(onClick = model::dismissStaleNotice) { Text(EntryStrings.RESTORED_OK) } },
        )
    }
}

@Composable
private fun ScheduledDialog(
    edit: ScheduledEdit,
    error: String?,
    saving: Boolean,
    onTextChange: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(edit) { mutableStateOf(edit.initial?.toString().orEmpty()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(edit) {
        focus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(CalendarStrings.SCHEDULED_DIALOG_TITLE) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onTextChange()
                },
                singleLine = true,
                label = { Text(CalendarStrings.SCHEDULED_FIELD) },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics {
                    contentDescription = CalendarStrings.SCHEDULED_DIALOG_TITLE
                    error?.let { message -> error(message) }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !saving) { Text(CalendarStrings.SCHEDULED_SAVE) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !saving) { Text(CalendarStrings.SCHEDULED_CANCEL) }
        },
    )
}

private val previewToday = LocalDate(2026, 11, 2)
private val previewPlaceA = Place(1, "West Clinic", null, 0xFF22B14CL)
private val previewPlaceB = Place(2, "East Center", null, 0xFFFF7F27L)

private fun previewState(withEntries: Boolean) = CalendarUiState(
    month = YearMonth(2026, 11),
    selected = previewToday,
    today = previewToday,
    canGoPrevious = true,
    canGoNext = true,
    marks = if (withEntries) {
        mapOf(
            previewToday to listOf(
                DayMark(previewPlaceA.id, previewPlaceA.name, previewPlaceA.color),
                DayMark(previewPlaceB.id, previewPlaceB.name, previewPlaceB.color),
            ),
        )
    } else {
        emptyMap()
    },
    summary = DaySummary(
        date = previewToday,
        places = if (withEntries) {
            listOf(
                PlaceDayCard(1, previewPlaceA, listOf(DayCardLine(1, "Checkup", false, 3, MinorAmount(1250), MinorAmount(3750))), MinorAmount(3750), scheduled = 5),
                PlaceDayCard(2, previewPlaceB, listOf(DayCardLine(1, "Checkup", false, 2, MinorAmount(2000), MinorAmount(4000))), MinorAmount(4000)),
            )
        } else {
            emptyList()
        },
        dayTotal = MinorAmount(if (withEntries) 7750 else 0),
    ),
)

@Preview
@Composable
private fun CalendarWithEntriesPreview() = ServiceCalendarTheme {
    CalendarContent(previewState(true), {}, {}, {}, {}, {}, { _, _ -> })
}

@Preview
@Composable
private fun CalendarEmptyDayPreview() = ServiceCalendarTheme {
    CalendarContent(previewState(false), {}, {}, {}, {}, {}, { _, _ -> })
}
