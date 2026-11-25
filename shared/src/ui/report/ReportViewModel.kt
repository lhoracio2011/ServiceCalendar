package app.servicecalendar.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.servicecalendar.AppContainer
import app.servicecalendar.data.PeriodReport
import app.servicecalendar.domain.AmountOutOfRange
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.ui.calendar.systemToday
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

typealias ReportSource = (from: LocalDate, to: LocalDate) -> Flow<PeriodReport>

data class ReportPeriod(val from: LocalDate, val to: LocalDate)

enum class ReportError {
    InvalidPeriod,

    AmountTooLarge,

    Unavailable,
}

sealed interface ReportUiState {
    val from: LocalDate
    val to: LocalDate

    data class Loading(override val from: LocalDate, override val to: LocalDate) : ReportUiState

    data class Empty(override val from: LocalDate, override val to: LocalDate) : ReportUiState

    data class Error(
        override val from: LocalDate,
        override val to: LocalDate,
        val reason: ReportError,
    ) : ReportUiState

    data class Data(val report: PeriodReport) : ReportUiState {
        override val from: LocalDate get() = report.from
        override val to: LocalDate get() = report.to
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ReportModel(
    private val source: ReportSource,
    today: () -> LocalDate,
) {
    private val _period = MutableStateFlow(defaultPeriod(today()))

    val period: StateFlow<ReportPeriod> = _period.asStateFlow()

    val initialState: ReportUiState
        get() = ReportUiState.Loading(period.value.from, period.value.to)

    val states: Flow<ReportUiState> = _period.flatMapLatest { stateFor(it) }

    fun setFrom(date: LocalDate) = _period.update { it.copy(from = date) }

    fun setTo(date: LocalDate) = _period.update { it.copy(to = date) }

    private fun stateFor(period: ReportPeriod): Flow<ReportUiState> {
        if (period.to < period.from) {
            return flowOf(ReportUiState.Error(period.from, period.to, ReportError.InvalidPeriod))
        }
        return flow { emitAll(source(period.from, period.to)) }
            .map { report ->
                if (report.places.isEmpty()) ReportUiState.Empty(report.from, report.to) else ReportUiState.Data(report)
            }
            .onStart<ReportUiState> { emit(ReportUiState.Loading(period.from, period.to)) }
            .catch { emit(ReportUiState.Error(period.from, period.to, it.toReportError())) }
    }

    private fun Throwable.toReportError(): ReportError = when {
        this is ValidationError && field == "period" -> ReportError.InvalidPeriod
        this is AmountOutOfRange -> ReportError.AmountTooLarge
        else -> ReportError.Unavailable
    }

    private fun defaultPeriod(today: LocalDate) = ReportPeriod(LocalDate(today.year, today.month, 1), today)
}

class ReportViewModel(
    source: ReportSource = AppContainer.instance.reportRepository::reportFlow,
    today: () -> LocalDate = ::systemToday,
) : ViewModel() {
    private val model = ReportModel(source, today)

    val state: StateFlow<ReportUiState> = model.states.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = model.initialState,
    )

    fun setFrom(date: LocalDate) = model.setFrom(date)

    fun setTo(date: LocalDate) = model.setTo(date)
}
