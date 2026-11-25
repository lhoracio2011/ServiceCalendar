package app.servicecalendar.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun `price times quantity`() {
        assertEquals(MinorAmount(3750), MinorAmount(1250).times(3))
    }

    @Test
    fun `zero price times any quantity gives zero`() {
        assertEquals(MinorAmount(0), MinorAmount(0).times(Int.MAX_VALUE))
    }

    @Test
    fun `addition`() {
        assertEquals(MinorAmount(5300), MinorAmount(3750) + MinorAmount(1550))
    }

    @Test
    fun `multiplication overflow is an error instead of a wrong number`() {
        assertFailsWith<ArithmeticException> { MinorAmount(Long.MAX_VALUE).times(2) }
        assertFailsWith<ArithmeticException> { MinorAmount(Long.MAX_VALUE / 2 + 1).times(2) }
    }

    @Test
    fun `multiplication at the exact limit still works`() {
        assertEquals(MinorAmount(Long.MAX_VALUE - 1), MinorAmount(Long.MAX_VALUE / 2).times(2))
    }

    @Test
    fun `addition overflow is an error instead of a wrong number`() {
        assertFailsWith<ArithmeticException> { MinorAmount(Long.MAX_VALUE) + MinorAmount(1) }
    }

    @Test
    fun `negative amount is rejected`() {
        assertFailsWith<IllegalArgumentException> { MinorAmount(-1) }
    }

    @Test
    fun `negative quantity is rejected`() {
        assertFailsWith<IllegalArgumentException> { MinorAmount(100).times(-1) }
    }
}
