package app.servicecalendar.money

import app.servicecalendar.domain.percentDone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyFormatTest {
    @Test
    fun `comma and dot both parse to the same amount`() {
        assertEquals(MinorAmount(1250), parseAmount("12,50"))
        assertEquals(MinorAmount(1250), parseAmount("12.50"))
    }

    @Test
    fun `whole numbers and one decimal digit`() {
        assertEquals(MinorAmount(1200), parseAmount("12"))
        assertEquals(MinorAmount(1250), parseAmount("12,5"))
        assertEquals(MinorAmount(5), parseAmount("0,05"))
        assertEquals(MinorAmount(0), parseAmount("0"))
    }

    @Test
    fun `surrounding spaces are ignored`() {
        assertEquals(MinorAmount(775), parseAmount("  7,75 "))
    }

    @Test
    fun `more than two decimal places is rejected`() {
        assertNull(parseAmount("12,505"))
        assertNull(parseAmount("12.501"))
        assertNull(parseAmount("1.250"))
    }

    @Test
    fun `negative values are rejected`() {
        assertNull(parseAmount("-12,50"))
        assertNull(parseAmount("-0"))
    }

    @Test
    fun `garbage is rejected`() {
        listOf("", " ", "abc", "12,", ",50", ".", "1,2,3", "1.2.3", "12,5a", "1e3", "R$ 12,50", "١٢").forEach {
            assertNull(parseAmount(it), "must reject '$it'")
        }
    }

    @Test
    fun `amount beyond the supported range is rejected`() {
        assertNull(parseAmount("99999999999999999999"))
    }

    @Test
    fun `formats with the pt-BR separator`() {
        assertEquals("37,50", formatAmount(MinorAmount(3750), ','))
    }

    @Test
    fun `formats with a dot separator`() {
        assertEquals("37.50", formatAmount(MinorAmount(3750), '.'))
    }

    @Test
    fun `pads cents and handles zero`() {
        assertEquals("0,05", formatAmount(MinorAmount(5), ','))
        assertEquals("0,00", formatAmount(MinorAmount(0), ','))
        assertEquals("12,00", formatAmount(MinorAmount(1200), ','))
    }

    @Test
    fun `format then parse round-trips`() {
        listOf(0L, 1L, 99L, 100L, 1250L, 3750L, 123456789L).forEach {
            assertEquals(MinorAmount(it), parseAmount(formatAmount(MinorAmount(it), ',')))
        }
    }

    @Test
    fun `percent has one decimal with the given separator`() {
        assertEquals("60,9", formatPercent(60.9, ','))
        assertEquals("60.9", formatPercent(60.9, '.'))
        assertEquals("0,0", formatPercent(0.0, ','))
        assertEquals("100,0", formatPercent(100.0, ','))
        assertEquals("150,0", formatPercent(150.0, ','))
    }

    @Test
    fun `percent of 67 out of 110 is 60 point 9`() {
        assertEquals("60,9", formatPercent(67 * 100.0 / 110, ','))
        assertEquals("60,9", formatPercent(percentDone(67, 110), ','))
    }

    @Test
    fun `percent rounds halves up without binary artifacts`() {
        assertEquals("0,2", formatPercent(0.15, ','))
        assertEquals("1,1", formatPercent(1.05, ','))
        assertEquals("61,0", formatPercent(60.95, ','))
        assertEquals("6,3", formatPercent(6.25, ','))
        assertEquals("6,2", formatPercent(6.2499, ','))
        assertEquals("33,3", formatPercent(100.0 / 3, ','))
        assertEquals("66,7", formatPercent(200.0 / 3, ','))
    }

    @Test
    fun `missing or non-finite percent is a dash`() {
        assertEquals("—", formatPercent(null, ','))
        assertEquals("—", formatPercent(Double.NaN, ','))
        assertEquals("—", formatPercent(Double.POSITIVE_INFINITY, ','))
    }
}
