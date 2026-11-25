package app.servicecalendar.ui.entry

import app.servicecalendar.data.DayEntryEditorData
import app.servicecalendar.data.DayEntryEditorRow
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import app.servicecalendar.ui.calendar.localMediumDate
import app.servicecalendar.ui.calendar.localWeekdayFull
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The day editor logic, tested without Compose or a database (state is plain snapshot values). */
class DayEntryEditorStateTest {
    private val date = LocalDate(2026, 11, 1)
    private val place = Place(id = 7, name = "Клініка А", address = null, color = 0xFF22B14CL)

    private val exam = 1L
    private val dressing = 2L

    /** A saved row: [quantity] at the historical rate [applied] (the current rate is [current]). */
    private fun savedRow(id: Long, name: String, quantity: Int, applied: Long, current: Long?) = DayEntryEditorRow(
        procedureId = id,
        procedureName = name,
        procedureArchived = false,
        currentRate = current?.let(::MinorAmount),
        savedQuantity = quantity,
        savedAppliedAmount = MinorAmount(applied),
    )

    private fun freshRow(id: Long, name: String, rate: Long) = DayEntryEditorRow(
        procedureId = id,
        procedureName = name,
        procedureArchived = false,
        currentRate = MinorAmount(rate),
        savedQuantity = null,
        savedAppliedAmount = null,
    )

    private fun data(
        rows: List<DayEntryEditorRow>,
        otherPlacesTotal: Long = 0,
        generation: Long = 5,
    ) = DayEntryEditorData(
        date = date,
        place = place,
        generation = generation,
        entryId = if (rows.any { it.savedQuantity != null }) 10 else null,
        rows = rows,
        otherPlacesTotal = MinorAmount(otherPlacesTotal),
    )

    /** "Огляд" 3 x 12.50 and "Перев'язка" 2 x 7.75, with 40.00 from other places. */
    private fun twoProcedures() = DayEntryEditorState(
        data(
            rows = listOf(
                savedRow(exam, "Огляд", quantity = 3, applied = 1250, current = 1250),
                savedRow(dressing, "Перев'язка", quantity = 2, applied = 775, current = 775),
            ),
            otherPlacesTotal = 4000,
        ),
    )

    @Test
    fun `initial fields show saved quantities and empty for new rows`() {
        val state = DayEntryEditorState(
            data(listOf(savedRow(exam, "Огляд", 3, 1250, 1250), freshRow(dressing, "Перев'язка", 775))),
        )
        assertEquals("3", state.text(exam))
        assertEquals("", state.text(dressing))
        assertFalse(state.isDirty)
        assertTrue(state.isValid)
    }

    @Test
    fun `empty field means zero`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "")
        assertEquals(0, state.quantity(exam))
        assertNull(state.rowError(exam))
        assertEquals(MinorAmount(0), state.lineTotal(exam))
        assertEquals(0, state.toQuantities()?.get(exam))
    }

    @Test
    fun `negative input is invalid and not saved`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "-1")
        assertNull(state.quantity(exam))
        assertNotNull(state.rowError(exam))
        assertNull(state.rowError(dressing))
        assertFalse(state.isValid)
        assertNull(state.toQuantities())
    }

    @Test
    fun `fractional input is invalid with dot and comma`() {
        for (text in listOf("1.5", "1,5", "2.0")) {
            val state = twoProcedures()
            state.updateQuantity(exam, text)
            assertNull(state.quantity(exam), text)
            assertNotNull(state.rowError(exam), text)
            assertNull(state.toQuantities(), text)
        }
    }

    @Test
    fun `non numeric input is invalid`() {
        for (text in listOf("abc", "1a", "+3", "٣")) {
            val state = twoProcedures()
            state.updateQuantity(exam, text)
            assertNull(state.quantity(exam), text)
            assertNotNull(state.rowError(exam), text)
        }
    }

    @Test
    fun `overflow sized input gives an error not a wrong number`() {
        for (text in listOf("2147483648", "99999999999999999999")) {
            val state = twoProcedures()
            state.updateQuantity(exam, text)
            assertNull(state.quantity(exam), text)
            assertNotNull(state.rowError(exam), text)
            assertNull(state.lineTotal(exam), text)
            assertNull(state.toQuantities(), text)
        }
    }

    @Test
    fun `line total overflow is an error on that row`() {
        val state = DayEntryEditorState(data(listOf(freshRow(exam, "Огляд", Long.MAX_VALUE / 2))))
        state.updateQuantity(exam, "3")
        assertNotNull(state.rowError(exam))
        assertNull(state.lineTotal(exam))
        assertFalse(state.isValid)
        assertNull(state.toQuantities())
    }

    @Test
    fun `place total overflow blocks saving`() {
        val state = DayEntryEditorState(
            data(listOf(freshRow(exam, "Огляд", Long.MAX_VALUE / 2), freshRow(dressing, "Перев'язка", Long.MAX_VALUE / 2))),
        )
        state.updateQuantity(exam, "1")
        state.updateQuantity(dressing, "2")
        assertNull(state.rowError(exam))
        assertNull(state.rowError(dressing))
        assertNull(state.placeTotal)
        assertNotNull(state.totalsError)
        assertFalse(state.isValid)
        assertNull(state.toQuantities())
    }

    @Test
    fun `day total overflow blocks saving`() {
        val state = DayEntryEditorState(
            data(listOf(freshRow(exam, "Огляд", Long.MAX_VALUE - 100)), otherPlacesTotal = 1000),
        )
        state.updateQuantity(exam, "1")
        assertNotNull(state.placeTotal)
        assertNull(state.dayTotal)
        assertNotNull(state.totalsError)
        assertNull(state.toQuantities())
    }

    @Test
    fun `fixing the value clears the error`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "-1")
        state.updateQuantity(exam, "4")
        assertNull(state.rowError(exam))
        assertEquals(4, state.quantity(exam))
        assertTrue(state.isValid)
    }

    @Test
    fun `saving is blocked while any row is invalid`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "5")
        state.updateQuantity(dressing, "x")
        assertFalse(state.isValid)
        assertNull(state.toQuantities())
        state.updateQuantity(dressing, "1")
        assertEquals(mapOf(exam to 5, dressing to 1), state.toQuantities())
    }

    @Test
    fun `initial totals come from saved lines and other places`() {
        val state = twoProcedures()
        assertEquals(MinorAmount(3750), state.lineTotal(exam))
        assertEquals(MinorAmount(1550), state.lineTotal(dressing))
        assertEquals(MinorAmount(5300), state.placeTotal)
        assertEquals(MinorAmount(9300), state.dayTotal)
    }

    @Test
    fun `changing quantity recomputes line place and day totals immediately`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "0")
        assertEquals(MinorAmount(0), state.lineTotal(exam))
        assertEquals(MinorAmount(1550), state.placeTotal)
        assertEquals(MinorAmount(5550), state.dayTotal)

        state.updateQuantity(dressing, "")
        assertEquals(MinorAmount(0), state.placeTotal)
        assertEquals(MinorAmount(4000), state.dayTotal)

        state.updateQuantity(exam, "4")
        assertEquals(MinorAmount(5000), state.lineTotal(exam))
        assertEquals(MinorAmount(5000), state.placeTotal)
        assertEquals(MinorAmount(9000), state.dayTotal)
    }

    @Test
    fun `invalid rows do not contribute to totals`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "abc")
        assertNull(state.lineTotal(exam))
        assertEquals(MinorAmount(1550), state.placeTotal)
        assertEquals(MinorAmount(5550), state.dayTotal)
    }

    @Test
    fun `new row uses current rate`() {
        val state = DayEntryEditorState(data(listOf(freshRow(exam, "Огляд", 1500))))
        state.updateQuantity(exam, "3")
        assertEquals(MinorAmount(4500), state.lineTotal(exam))
    }

    @Test
    fun `clearing a saved row and typing 3 again keeps the historical price`() {
        // The current rate is already 15.00 but the saved line has the historical 12.50.
        val state = DayEntryEditorState(data(listOf(savedRow(exam, "Огляд", 3, applied = 1250, current = 1500))))
        assertEquals(MinorAmount(3750), state.lineTotal(exam))

        state.updateQuantity(exam, "")
        assertEquals(MinorAmount(0), state.lineTotal(exam))
        state.updateQuantity(exam, "3")

        assertEquals(MinorAmount(3750), state.lineTotal(exam))
        assertEquals(MinorAmount(3750), state.placeTotal)
        assertFalse(state.isDirty)
    }

    @Test
    fun `saved row without current rate still uses historical price`() {
        val state = DayEntryEditorState(data(listOf(savedRow(exam, "Огляд", 3, applied = 1250, current = null))))
        state.updateQuantity(exam, "4")
        assertEquals(MinorAmount(5000), state.lineTotal(exam))
    }

    @Test
    fun `toQuantities returns every row with zero for empty`() {
        val state = DayEntryEditorState(
            data(listOf(savedRow(exam, "Огляд", 3, 1250, 1250), freshRow(dressing, "Перев'язка", 775))),
        )
        state.updateQuantity(exam, "")
        assertEquals(mapOf(exam to 0, dressing to 0), state.toQuantities())
        state.updateQuantity(dressing, "2")
        assertEquals(mapOf(exam to 0, dressing to 2), state.toQuantities())
    }

    @Test
    fun `editing never changes the loaded data so cancel leaves the stored entry unchanged`() {
        val loaded = data(listOf(savedRow(exam, "Огляд", 3, 1250, 1250), savedRow(dressing, "Перев'язка", 2, 775, 775)))
        val state = DayEntryEditorState(loaded)
        state.updateQuantity(exam, "9")
        state.updateQuantity(dressing, "")
        // Only a save changes the stored data; the state exposes data only through toQuantities().
        assertEquals(3, loaded.rows[0].savedQuantity)
        assertEquals(2, loaded.rows[1].savedQuantity)
        assertEquals(MinorAmount(1250), loaded.rows[0].savedAppliedAmount)
        // A new state from the same data (reopening) starts clean.
        val reopened = DayEntryEditorState(loaded)
        assertEquals("3", reopened.text(exam))
        assertEquals("2", reopened.text(dressing))
        assertFalse(reopened.isDirty)
    }

    @Test
    fun `isDirty reacts to changes and returns to clean when value is restored`() {
        val state = twoProcedures()
        assertFalse(state.isDirty)
        state.updateQuantity(exam, "4")
        assertTrue(state.isDirty)
        state.updateQuantity(exam, "3")
        assertFalse(state.isDirty)
    }

    @Test
    fun `leading zeros equal the saved value and are not dirty`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "03")
        assertEquals(3, state.quantity(exam))
        assertFalse(state.isDirty)
    }

    @Test
    fun `empty field on a new row is not dirty but a value is`() {
        val state = DayEntryEditorState(data(listOf(freshRow(exam, "Огляд", 1250))))
        assertFalse(state.isDirty)
        state.updateQuantity(exam, "0")
        assertFalse(state.isDirty)
        state.updateQuantity(exam, "1")
        assertTrue(state.isDirty)
    }

    @Test
    fun `invalid text counts as unsaved change`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "abc")
        assertTrue(state.isDirty)
    }

    @Test
    fun `clearing a saved row is a change`() {
        val state = twoProcedures()
        state.updateQuantity(exam, "")
        assertTrue(state.isDirty)
    }

    @Test
    fun `repository validation error is shown on its row and cleared on edit`() {
        val state = twoProcedures()
        state.applyError(ValidationError("quantity.$exam", "the procedure has no rate at this place"))
        assertNotNull(state.rowError(exam))
        assertNull(state.rowError(dressing))
        assertFalse(state.isValid)
        state.updateQuantity(exam, "2")
        assertNull(state.rowError(exam))
        assertTrue(state.isValid)
    }

    @Test
    fun `unknown validation field becomes a general error`() {
        val state = twoProcedures()
        state.applyError(ValidationError("placeId", "the place is archived"))
        assertNotNull(state.generalError)
        state.showGeneralError("Failure")
        assertEquals("Failure", state.generalError)
    }

    @Test
    fun `entry date is formatted with weekday month name and year`() {
        // Names come from the system locale (covered by LocaleDates*Test); this checks the composition.
        fun expected(d: LocalDate) = localWeekdayFull(d.dayOfWeek) + ", " + localMediumDate(d)
        assertEquals(expected(LocalDate(2026, 11, 2)), formatEntryDate(LocalDate(2026, 11, 2)))
        assertEquals(localWeekdayFull(DayOfWeek.MONDAY), formatEntryDate(LocalDate(2026, 11, 2)).substringBefore(", "))
        assertEquals(expected(LocalDate(2026, 11, 1)), formatEntryDate(LocalDate(2026, 11, 1)))
        assertEquals(expected(LocalDate(2026, 12, 31)), formatEntryDate(LocalDate(2026, 12, 31)))
    }

    private fun withScheduled(scheduled: Int?) = DayEntryEditorState(
        data(listOf(savedRow(exam, "Огляд", 3, 1250, 1250), savedRow(dressing, "Перев'язка", 2, 775, 775)))
            .copy(scheduled = scheduled),
    )

    @Test
    fun `scheduled is prefilled and clean until edited`() {
        val state = withScheduled(10)
        assertEquals("10", state.scheduledText)
        assertEquals(10, state.scheduled)
        assertFalse(state.isDirty)
        assertEquals("", withScheduled(null).scheduledText)
    }

    @Test
    fun `editing scheduled makes the editor dirty and back to saved is clean`() {
        val state = withScheduled(10)
        state.updateScheduled("12")
        assertTrue(state.isDirty)
        assertEquals(12, state.scheduled)
        state.updateScheduled("10")
        assertFalse(state.isDirty)
        state.updateScheduled("")
        assertTrue(state.isDirty)
        assertNull(state.scheduled)
        assertTrue(state.isValid)
        val none = withScheduled(null)
        none.updateScheduled("0")
        assertTrue(none.isDirty)
        assertEquals(0, none.scheduled)
    }

    @Test
    fun `invalid scheduled input blocks saving`() {
        for (text in listOf("-1", "1.5", "abc", "99999999999")) {
            val state = withScheduled(null)
            state.updateScheduled(text)
            assertNotNull(state.scheduledError, text)
            assertFalse(state.isValid, text)
            assertTrue(state.isDirty, text)
            assertNull(state.toQuantities(), text)
        }
    }

    @Test
    fun `live completed and percent follow the typed quantities`() {
        val state = withScheduled(10)
        assertEquals(5L, state.completed)
        assertEquals(50.0, state.percent)
        state.updateQuantity(exam, "5")
        assertEquals(7L, state.completed)
        assertEquals(70.0, state.percent)
        state.updateScheduled("")
        assertNull(state.percent)
        state.updateScheduled("0")
        assertNull(state.percent)
    }

    @Test
    fun `repository rejection of scheduled is shown and cleared on edit`() {
        val state = withScheduled(null)
        state.applyError(ValidationError("scheduled", "scheduled count cannot be negative"))
        assertNotNull(state.scheduledError)
        assertFalse(state.isValid)
        state.updateScheduled("4")
        assertNull(state.scheduledError)
    }
}
