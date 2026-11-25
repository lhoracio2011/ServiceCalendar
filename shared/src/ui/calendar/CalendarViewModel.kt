package app.servicecalendar.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.servicecalendar.AppContainer
import app.servicecalendar.data.DayMark
import app.servicecalendar.data.DaySummary
import app.servicecalendar.data.WorkEntryRepository
import app.servicecalendar.money.MinorAmount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.todayIn
import kotlin.time.Clock

internal fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

data class CalendarUiState(
    val month: YearMonth,
    val selected: LocalDate,
    val today: LocalDate,
    val canGoPrevious: Boolean,
    val canGoNext: Boolean,
    val marks: Map<LocalDate, List<DayMark>>,
    val summary: DaySummary?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    repository: WorkEntryRepository = AppContainer.instance.workEntryRepository,
    today: () -> LocalDate = ::systemToday,
    generation: StateFlow<Long?> = AppContainer.instance.dataStore.dataGeneration,
) : ViewModel() {
    internal val scheduledEdit = ScheduledEditModel(
        scope = viewModelScope,
        generation = generation,
        write = { date, placeId, value, opened -> repository.setScheduled(date, placeId, value, opened) },
    )

    private val todayState = MutableStateFlow(today())
    private val position = MutableStateFlow(initialPosition(todayState.value))
    private val clock = today

    private val marks = position
        .map { it.month }
        .distinctUntilChanged()
        .flatMapLatest { month ->
            repository.monthMarks(month)
                .map { month to it }
                .catch { emit(month to emptyMap()) }
        }

    private val summary = position
        .map { it.selected }
        .distinctUntilChanged()
        .flatMapLatest { date ->
            repository.dayCards(date)
                .catch { emit(DaySummary(date, emptyList(), MinorAmount(0))) }
        }

    val state: StateFlow<CalendarUiState> = combine(position, todayState, marks, summary) { pos, now, markPair, day ->
        CalendarUiState(
            month = pos.month,
            selected = pos.selected,
            today = now,
            canGoPrevious = pos.canGoPrevious,
            canGoNext = pos.canGoNext,
            marks = if (markPair.first == pos.month) markPair.second else emptyMap(),
            summary = day.takeIf { it.date == pos.selected },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = position.value.let {
            CalendarUiState(it.month, it.selected, todayState.value, it.canGoPrevious, it.canGoNext, emptyMap(), null)
        },
    )

    fun previousMonth() = position.update { it.previousMonth() }

    fun nextMonth() = position.update { it.nextMonth() }

    fun select(date: LocalDate) = position.update { it.select(date) }

    fun goToToday() {
        val now = clock()
        todayState.value = now
        position.update { it.goToToday(now) }
    }
}
