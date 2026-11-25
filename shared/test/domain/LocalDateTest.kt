package app.servicecalendar.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalDateTest {
    @Test
    fun `storage text is zero padded`() {
        assertEquals("2026-02-01", LocalDate(2026, 2, 1).toStorageText())
        assertEquals("2026-11-01", LocalDate(2026, 11, 1).toStorageText())
        assertEquals("0987-06-05", LocalDate(987, 6, 5).toStorageText())
    }

    @Test
    fun `round trip keeps the date`() {
        for (date in listOf(LocalDate(2026, 11, 1), LocalDate(2026, 12, 31), LocalDate(2027, 1, 1))) {
            assertEquals(date, parseStorageDate(date.toStorageText()))
        }
    }

    @Test
    fun `leap day is accepted`() {
        val leap = parseStorageDate("2028-02-29")
        assertEquals(LocalDate(2028, 2, 29), leap)
        assertEquals("2028-02-29", leap.toStorageText())
    }

    @Test
    fun `non leap february 29 is rejected`() {
        val e = assertFailsWith<ValidationError> { parseStorageDate("2026-02-29") }
        assertEquals("date", e.field)
    }

    @Test
    fun `malformed text is rejected`() {
        val bad = listOf(
            "", " ", "2026-13-01", "2026-00-10", "2026-01-00", "2026-01-32", "2026-04-31",
            "2026-2-1", "2026-02-1", "2026-2-01", " 2026-02-01", "2026-02-01 ", "2026-02-01\n",
            "2026/02/01", "20260201", "26-02-01", "02-01-2026", "+026-02-01", "-026-02-01",
            "2026-02-0a", "abcd-ef-gh", "2026-02-011", "12026-02-01", "٢٠٢٦-٠٢-٠١",
            "2026-02-01T00:00:00Z",
        )
        for (text in bad) {
            val e = assertFailsWith<ValidationError>("\"$text\" must be rejected") { parseStorageDate(text) }
            assertEquals("date", e.field)
        }
    }
}
