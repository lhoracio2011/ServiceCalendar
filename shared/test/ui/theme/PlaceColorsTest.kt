package app.servicecalendar.ui.theme

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceColorsTest {
    @Test
    fun `picks only from free colors while some are free`() {
        val used = PlaceColorPalette.drop(1)
        repeat(20) {
            assertEquals(PlaceColorPalette.first(), pickPlaceColor(used, Random(it)))
        }
    }

    @Test
    fun `repeats a palette color when all are used`() {
        repeat(20) {
            assertTrue(pickPlaceColor(PlaceColorPalette, Random(it)) in PlaceColorPalette)
        }
    }

    @Test
    fun `empty usage can give any palette color`() {
        val picked = (0 until 200).map { pickPlaceColor(emptyList(), Random(it)) }.toSet()
        assertTrue(picked.size > 1, "colors should vary")
    }
}
