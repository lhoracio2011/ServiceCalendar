package app.servicecalendar.domain

import app.servicecalendar.model.WorkLine
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DayTotalsTest {
    private fun line(id: Long, price: Long, quantity: Int) =
        WorkLine(entryId = 1, procedureId = id, quantity = quantity, appliedAmountMinor = MinorAmount(price))

    @Test
    fun `line total is price times quantity`() {
        assertEquals(MinorAmount(3750), lineTotal(MinorAmount(1250), 3))
    }

    @Test
    fun `zero quantity gives zero`() {
        assertEquals(MinorAmount(0), lineTotal(MinorAmount(1250), 0))
    }

    @Test
    fun `negative quantity is a validation error`() {
        val e = assertFailsWith<ValidationError> { lineTotal(MinorAmount(1250), -1) }
        assertEquals("quantity", e.field)
    }

    @Test
    fun `line total overflow is amount out of range`() {
        assertFailsWith<AmountOutOfRange> { lineTotal(MinorAmount(Long.MAX_VALUE), 2) }
    }

    @Test
    fun `empty sums give zero`() {
        assertEquals(MinorAmount(0), sumAmounts(emptyList()))
        assertEquals(MinorAmount(0), placeTotal(emptyList()))
        assertEquals(MinorAmount(0), dayTotal(emptyList()))
    }

    @Test
    fun `sum of amounts`() {
        assertEquals(MinorAmount(7750), sumAmounts(listOf(MinorAmount(3750), MinorAmount(4000))))
    }

    @Test
    fun `sum overflow is amount out of range`() {
        assertFailsWith<AmountOutOfRange> {
            sumAmounts(listOf(MinorAmount(Long.MAX_VALUE), MinorAmount(1)))
        }
    }

    @Test
    fun `place total of one line`() {
        assertEquals(MinorAmount(3750), placeTotal(listOf(line(1, 1250, 3))))
    }

    @Test
    fun `two procedures at place A give 53 and the day totals 93`() {
        val placeA = placeTotal(listOf(line(1, 1250, 3), line(2, 775, 2)))
        assertEquals(MinorAmount(5300), placeA)
        assertEquals(MinorAmount(9300), dayTotal(listOf(placeA, MinorAmount(4000))))
    }

    @Test
    fun `place total with zero quantity line ignores it`() {
        assertEquals(MinorAmount(3750), placeTotal(listOf(line(1, 1250, 3), line(2, 775, 0))))
    }

    @Test
    fun `place total overflow is amount out of range`() {
        assertFailsWith<AmountOutOfRange> {
            placeTotal(listOf(line(1, Long.MAX_VALUE, 1), line(2, 1, 1)))
        }
        assertFailsWith<AmountOutOfRange> { placeTotal(listOf(line(1, Long.MAX_VALUE, 2))) }
    }

    @Test
    fun `completed count is the sum of quantities and does not overflow Int`() {
        assertEquals(0L, completedCount(emptyList()))
        assertEquals(67L, completedCount(listOf(40, 20, 7)))
        assertEquals(2L * Int.MAX_VALUE, completedCount(listOf(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertFailsWith<ValidationError> { completedCount(listOf(1, -1)) }
    }

    @Test
    fun `percent done is R over A times 100 with one decimal`() {
        assertEquals(60.9, percentDone(67, 110))
        assertEquals(100.0, percentDone(5, 5))
        assertEquals(0.0, percentDone(0, 7))
        assertEquals(150.0, percentDone(3, 2))
        assertEquals(33.3, percentDone(1, 3))
        assertEquals(66.7, percentDone(2, 3))
    }

    @Test
    fun `percent done rounds halves up`() {
        // 1 / 16 = 6.25 %, 3 / 80 = 3.75 %, 1 / 400 = 0.25 %
        assertEquals(6.3, percentDone(1, 16))
        assertEquals(3.8, percentDone(3, 80))
        assertEquals(0.3, percentDone(1, 400))
        assertEquals(0.0, percentDone(1, 2001))
        assertEquals(0.1, percentDone(1, 2000))
    }

    @Test
    fun `percent done is null without a positive scheduled count`() {
        assertNull(percentDone(5, null))
        assertNull(percentDone(5, 0))
        assertNull(percentDone(0, 0))
        assertNull(percentDone(5, -1))
    }

    @Test
    fun `percent done handles extreme counts`() {
        assertEquals(100.0, percentDone(Int.MAX_VALUE.toLong(), Int.MAX_VALUE))
        assertEquals(0.0, percentDone(1, Int.MAX_VALUE))
        assertEquals(200000.0 * Int.MAX_VALUE, percentDone(2000L * Int.MAX_VALUE, 1))
    }
}
