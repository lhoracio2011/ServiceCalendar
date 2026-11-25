package app.servicecalendar.ui.report

import app.servicecalendar.data.PeriodReport
import app.servicecalendar.data.PlaceReportRow
import app.servicecalendar.domain.AmountOutOfRange
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/**
 * The report screen logic without a database or Compose. The data source is supplied as a function
 * (`ReportSource`), and recalculation after a save on another screen is simulated by bumping a "revision".
 * It tests [ReportModel], which holds all the logic that `ReportViewModel` merely wraps in `stateIn`.
 */
class ReportViewModelTest {
    private val today = LocalDate(2026, 11, 10)
    private val nov1 = LocalDate(2026, 11, 1)
    private val nov2 = LocalDate(2026, 11, 2)

    private val placeA = Place(1, "Клініка А", null, 0xFF22B14CL)
    private val placeB = Place(2, "Клініка Б", null, 0xFFFF7F27L)

    /** A fake source: the report depends on a "revision" that tests bump as a save would. */
    private class FakeSource(var rowsFor: (LocalDate, LocalDate) -> List<PlaceReportRow>) {
        val revision = MutableStateFlow(0)
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()

        val source: ReportSource = { from, to ->
            calls += from to to
            revision.map {
                val rows = rowsFor(from, to)
                PeriodReport(from, to, rows, MinorAmount(rows.sumOf { it.total.minor }))
            }
        }
    }

    private fun row(place: Place, minor: Long, days: Int = 1) = PlaceReportRow(place, MinorAmount(minor), days)

    private fun twoPlaces(from: LocalDate, to: LocalDate) = listOf(row(placeA, 5000, 2), row(placeB, 4000))

    /** Collects the model's states into a channel while the returned job is active. */
    private fun CoroutineScope.collect(model: ReportModel, into: Channel<ReportUiState>): Job =
        launch { model.states.collect { into.send(it) } }

    private suspend fun Channel<ReportUiState>.next(): ReportUiState = withTimeout(10_000) { receive() }

    private fun modelTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    @Test
    fun `default period is the first of the current month to today`() = modelTest {
        val fake = FakeSource(::twoPlaces)
        val model = ReportModel(fake.source) { today }
        assertEquals(ReportPeriod(nov1, today), model.period.value)
        assertEquals(ReportUiState.Loading(nov1, today), model.initialState)

        val states = Channel<ReportUiState>(Channel.UNLIMITED)
        val job = collect(model, states)
        try {
            assertEquals(ReportUiState.Loading(nov1, today), states.next())
            val data = assertIs<ReportUiState.Data>(states.next())
            assertEquals(nov1, data.from)
            assertEquals(today, data.to)
            assertEquals(MinorAmount(9000), data.report.total)
            assertEquals(listOf(nov1 to today), fake.calls)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `first day of the month gives a one day period`() = modelTest {
        val model = ReportModel(FakeSource(::twoPlaces).source) { nov1 }
        assertEquals(ReportPeriod(nov1, nov1), model.period.value)
    }

    @Test
    fun `report without places is the empty state`() = modelTest {
        val fake = FakeSource { _, _ -> emptyList() }
        val model = ReportModel(fake.source) { today }
        val states = Channel<ReportUiState>(Channel.UNLIMITED)
        val job = collect(model, states)
        try {
            states.next() // Loading
            assertEquals(ReportUiState.Empty(nov1, today), states.next())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `recalculates after a save elsewhere`() = modelTest {
        val fake = FakeSource(::twoPlaces)
        val model = ReportModel(fake.source) { today }
        val states = Channel<ReportUiState>(Channel.UNLIMITED)
        val job = collect(model, states)
        try {
            states.next() // Loading
            assertEquals(MinorAmount(9000), assertIs<ReportUiState.Data>(states.next()).report.total)

            // A save on another screen: the source returns new totals.
            fake.rowsFor = { _, _ -> listOf(row(placeA, 6550, 2), row(placeB, 4000)) }
            fake.revision.value += 1
            val updated = assertIs<ReportUiState.Data>(states.next())
            assertEquals(MinorAmount(10550), updated.report.total)

            // All entries were removed: Data turns into Empty.
            fake.rowsFor = { _, _ -> emptyList() }
            fake.revision.value += 1
            assertEquals(ReportUiState.Empty(nov1, today), states.next())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `reversed period is an error state and does not query the source`() = modelTest {
        val fake = FakeSource(::twoPlaces)
        val model = ReportModel(fake.source) { today }
        val states = Channel<ReportUiState>(Channel.UNLIMITED)
        val job = collect(model, states)
        try {
            states.next() // Loading
            states.next() // Data
            val before = fake.calls.size

            model.setFrom(LocalDate(2026, 11, 20))
            assertEquals(ReportUiState.Error(LocalDate(2026, 11, 20), today, ReportError.InvalidPeriod), states.next())
            assertEquals(before, fake.calls.size)

            // Fixing the date brings the report back.
            model.setTo(LocalDate(2026, 11, 25))
            assertEquals(ReportUiState.Loading(LocalDate(2026, 11, 20), LocalDate(2026, 11, 25)), states.next())
            val data = assertIs<ReportUiState.Data>(states.next())
            assertEquals(LocalDate(2026, 11, 25), data.to)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `source failures map to error states`() = modelTest {
        // The source itself rejects an invalid period (as ReportRepository.reportFlow does).
        val period = ReportModel({ _, _ -> throw ValidationError("period", "x") }) { today }
        val amount = ReportModel({ _, _ -> flow<PeriodReport> { throw AmountOutOfRange() } }) { today }
        val other = ReportModel({ _, _ -> flow<PeriodReport> { throw IllegalStateException("boom") } }) { today }

        suspend fun lastOf(model: ReportModel): ReportUiState {
            val states = Channel<ReportUiState>(Channel.UNLIMITED)
            val job = collect(model, states)
            try {
                states.next() // Loading
                return states.next()
            } finally {
                job.cancel()
            }
        }
        assertEquals(ReportError.InvalidPeriod, assertIs<ReportUiState.Error>(lastOf(period)).reason)
        assertEquals(ReportError.AmountTooLarge, assertIs<ReportUiState.Error>(lastOf(amount)).reason)
        assertEquals(ReportError.Unavailable, assertIs<ReportUiState.Error>(lastOf(other)).reason)
    }

    @Test
    fun `changing from and to updates the period and the state`() = modelTest {
        val fake = FakeSource { from, to ->
            // The amount depends on the period length, which shows the query used the new dates.
            listOf(row(placeA, (to.toEpochDays() - from.toEpochDays() + 1) * 100))
        }
        val model = ReportModel(fake.source) { today }
        val states = Channel<ReportUiState>(Channel.UNLIMITED)
        val job = collect(model, states)
        try {
            states.next() // Loading
            assertEquals(MinorAmount(1000), assertIs<ReportUiState.Data>(states.next()).report.total)

            model.setFrom(nov2)
            assertEquals(ReportPeriod(nov2, today), model.period.value)
            assertIs<ReportUiState.Loading>(states.next())
            assertEquals(MinorAmount(900), assertIs<ReportUiState.Data>(states.next()).report.total)

            model.setTo(nov2)
            assertEquals(ReportPeriod(nov2, nov2), model.period.value)
            assertIs<ReportUiState.Loading>(states.next())
            val one = assertIs<ReportUiState.Data>(states.next())
            assertEquals(MinorAmount(100), one.report.total)
            assertEquals(nov2, one.from)
            assertEquals(nov2, one.to)
            assertEquals(listOf(nov1 to today, nov2 to today, nov2 to nov2), fake.calls)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `selection survives the collector going away and coming back`() = modelTest {
        val fake = FakeSource(::twoPlaces)
        val model = ReportModel(fake.source) { today }
        val first = Channel<ReportUiState>(Channel.UNLIMITED)
        val firstJob = collect(model, first)
        first.next() // Loading
        first.next() // Data
        model.setFrom(nov2)
        model.setTo(LocalDate(2026, 11, 5))
        // The screen is gone (composition destroyed) while the model, held by a ViewModel, remains.
        firstJob.cancel()

        val second = Channel<ReportUiState>(Channel.UNLIMITED)
        val secondJob = collect(model, second)
        try {
            assertEquals(ReportPeriod(nov2, LocalDate(2026, 11, 5)), model.period.value)
            assertEquals(ReportUiState.Loading(nov2, LocalDate(2026, 11, 5)), second.next())
            val data = assertIs<ReportUiState.Data>(second.next())
            assertEquals(nov2, data.from)
            assertEquals(LocalDate(2026, 11, 5), data.to)
        } finally {
            secondJob.cancel()
        }
        assertTrue(fake.calls.last() == nov2 to LocalDate(2026, 11, 5))
    }

    @Test
    fun `source is not called for the same period twice in a row`() = modelTest {
        val fake = FakeSource(::twoPlaces)
        val model = ReportModel(fake.source) { today }
        val states = Channel<ReportUiState>(Channel.UNLIMITED)
        val job = collect(model, states)
        try {
            states.next()
            states.next()
            model.setFrom(nov1) // the same date
            fake.revision.value += 1 // recalculation of the same period
            assertIs<ReportUiState.Data>(states.next())
            assertEquals(1, fake.calls.size)
        } finally {
            job.cancel()
        }
    }
}
