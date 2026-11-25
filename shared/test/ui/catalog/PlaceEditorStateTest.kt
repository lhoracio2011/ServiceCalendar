package app.servicecalendar.ui.catalog

import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.data.PlaceWithRates
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.model.Place
import app.servicecalendar.model.PlaceRate
import app.servicecalendar.model.Procedure
import app.servicecalendar.money.MinorAmount
import app.servicecalendar.money.formatAmount
import app.servicecalendar.ui.theme.PlaceColorPalette
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The place editor logic, tested without Compose or a database (state is plain snapshot values). */
class PlaceEditorStateTest {
    private val exam = Procedure(id = 1, name = "Огляд")
    private val cleaning = Procedure(id = 2, name = "Чистка")
    private val catalog = listOf(exam, cleaning)

    private fun newState(
        used: Collection<Long> = emptyList(),
        random: Random = Random(1),
    ): PlaceEditorState = PlaceEditorState.forNewPlace(used, random).also {
        it.updateCatalog(catalog)
    }

    /** A new editor with a valid name; only the prices remain to be set. */
    private fun validState(): PlaceEditorState = newState().also { it.updateName("Клініка") }

    @Test
    fun `price with comma and dot gives the same minor amount`() {
        for (text in listOf("12,50", "12.50")) {
            val state = validState()
            state.toggleProcedure(exam.id, true)
            state.updatePrice(exam.id, text)
            assertEquals(mapOf(exam.id to 1250L), state.toDraft()?.rates, "input \"$text\"")
        }
    }

    @Test
    fun `whole number price is converted to minor units`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "20")
        assertEquals(mapOf(exam.id to 2000L), state.toDraft()?.rates)
    }

    @Test
    fun `invalid price text marks only that procedure row`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "12,505")
        state.toggleProcedure(cleaning.id, true)
        state.updatePrice(cleaning.id, "20,00")

        assertNull(state.toDraft())
        assertNotNull(state.rateError(exam.id))
        assertNull(state.rateError(cleaning.id))
        assertNull(state.nameError)
    }

    @Test
    fun `garbage and negative price are rejected`() {
        for (text in listOf("abc", "-5", "1,2,3", "")) {
            val state = validState()
            state.toggleProcedure(exam.id, true)
            state.updatePrice(exam.id, text)
            assertNull(state.toDraft(), "input \"$text\"")
            assertNotNull(state.rateError(exam.id), "input \"$text\"")
        }
    }

    @Test
    fun `editing a price clears its error`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "oops")
        assertNull(state.toDraft())
        assertNotNull(state.rateError(exam.id))

        state.updatePrice(exam.id, "10")
        assertNull(state.rateError(exam.id))
        assertNotNull(state.toDraft())
    }

    @Test
    fun `invalid text in an unselected row is ignored`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "12,50")
        state.updatePrice(cleaning.id, "abc")

        val draft = assertNotNull(state.toDraft())
        assertEquals(mapOf(exam.id to 1250L), draft.rates)
        assertNull(state.rateError(cleaning.id))
    }

    @Test
    fun `address is optional and trimmed`() {
        val state = validState()
        assertNull(state.toDraft()?.address)
        state.updateAddress("  вул. Лісова, 5 ")
        assertEquals("вул. Лісова, 5", state.toDraft()?.address)
    }

    @Test
    fun `procedures are not selected by default and place can have no rates`() {
        val state = validState()
        assertFalse(state.isSelected(exam.id))
        assertEquals(emptyMap(), state.toDraft()?.rates)
    }

    @Test
    fun `deselected procedure has no rate in the draft`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "12,50")
        state.toggleProcedure(cleaning.id, true)
        state.updatePrice(cleaning.id, "20,00")
        state.toggleProcedure(exam.id, false)

        assertEquals(mapOf(cleaning.id to 2000L), state.toDraft()?.rates)
    }

    @Test
    fun `reselecting a procedure keeps the typed price`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "12,50")
        state.toggleProcedure(exam.id, false)
        state.toggleProcedure(exam.id, true)

        assertEquals("12,50", state.priceText(exam.id))
        assertEquals(mapOf(exam.id to 1250L), state.toDraft()?.rates)
    }

    @Test
    fun `selected procedure without a price is an error on its row`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)

        assertNull(state.toDraft())
        assertNotNull(state.rateError(exam.id))
    }

    @Test
    fun `selected procedure that left the catalog is not saved`() {
        val state = validState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "12,50")
        state.toggleProcedure(cleaning.id, true)
        state.updatePrice(cleaning.id, "20,00")

        state.updateCatalog(listOf(cleaning))

        assertEquals(mapOf(cleaning.id to 2000L), state.toDraft()?.rates)
    }

    @Test
    fun `blank name is a name error`() {
        for (blank in listOf("", "   ")) {
            val state = newState()
            state.updateName(blank)
            assertNull(state.toDraft())
            assertNotNull(state.nameError)
        }
    }

    @Test
    fun `editing the name clears the name error`() {
        val state = newState()
        assertNull(state.toDraft())
        assertNotNull(state.nameError)

        state.updateName("Клініка")
        assertNull(state.nameError)
    }

    @Test
    fun `all errors are reported at once`() {
        val state = newState()
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "abc")

        assertNull(state.toDraft())
        assertNotNull(state.nameError)
        assertNotNull(state.rateError(exam.id))
    }

    @Test
    fun `validation error fields are mapped to name color and rate rows`() {
        val state = validState()
        state.applyError(ValidationError("name", "name must not be blank"))
        assertNotNull(state.nameError)

        state.applyError(ValidationError("color", "color is outside the ARGB range"))
        assertNotNull(state.colorError)

        state.applyError(ValidationError("rate.2", "price must not be negative"))
        assertNotNull(state.rateError(cleaning.id))
        assertNull(state.rateError(exam.id))
        assertNull(state.generalError)

        state.applyError(ValidationError("id", "place not found"))
        assertNotNull(state.generalError)
    }

    @Test
    fun `chosen color goes to the draft`() {
        val state = validState()
        state.updateColor(PlaceColorPalette[3])
        assertEquals(PlaceColorPalette[3], state.toDraft()?.color)
    }

    @Test
    fun `new place draft has no id and uses minor units`() {
        val state = newState()
        state.updateName("  Клініка ")
        state.updateAddress("Центр")
        state.updateColor(PlaceColorPalette[1])
        state.toggleProcedure(exam.id, true)
        state.updatePrice(exam.id, "12,50")
        state.toggleProcedure(cleaning.id, true)
        state.updatePrice(cleaning.id, "20.00")

        assertEquals(
            PlaceDraft(
                id = null,
                name = "Клініка",
                address = "Центр",
                color = PlaceColorPalette[1],
                rates = mapOf(exam.id to 1250L, cleaning.id to 2000L),
            ),
            state.toDraft(),
        )
    }

    private fun existing(): PlaceWithRates = PlaceWithRates(
        place = Place(id = 7, name = "Ветклініка", address = "вул. Лісова, 5", color = PlaceColorPalette[4]),
        rates = listOf(
            PlaceRate(placeId = 7, procedureId = exam.id, amountMinor = MinorAmount(1250)),
            PlaceRate(placeId = 7, procedureId = cleaning.id, amountMinor = MinorAmount(2000)),
        ),
    )

    @Test
    fun `existing place prefills fields and prices via formatAmount`() {
        val state = PlaceEditorState.forExisting(existing(), decimalSeparator = ',')
        state.updateCatalog(catalog)

        assertEquals(7L, state.placeId)
        assertEquals("Ветклініка", state.name)
        assertEquals("вул. Лісова, 5", state.address)
        assertEquals(PlaceColorPalette[4], state.color)
        assertTrue(state.isSelected(exam.id))
        assertEquals(formatAmount(MinorAmount(1250), ','), state.priceText(exam.id))
        assertEquals("12,50", state.priceText(exam.id))
        assertEquals("20,00", state.priceText(cleaning.id))
    }

    @Test
    fun `existing place without address has an empty address field`() {
        val place = existing().let { it.copy(place = it.place.copy(address = null)) }
        val state = PlaceEditorState.forExisting(place, decimalSeparator = ',')
        assertEquals("", state.address)
    }

    @Test
    fun `existing place unchanged builds the same draft with its id`() {
        val state = PlaceEditorState.forExisting(existing(), decimalSeparator = ',')
        state.updateCatalog(catalog)

        assertEquals(
            PlaceDraft(
                id = 7,
                name = "Ветклініка",
                address = "вул. Лісова, 5",
                color = PlaceColorPalette[4],
                rates = mapOf(exam.id to 1250L, cleaning.id to 2000L),
            ),
            state.toDraft(),
        )
    }

    @Test
    fun `existing place can drop a procedure and change a price`() {
        val state = PlaceEditorState.forExisting(existing(), decimalSeparator = ',')
        state.updateCatalog(catalog)
        state.toggleProcedure(cleaning.id, false)
        state.updatePrice(exam.id, "13.00")

        assertEquals(mapOf(exam.id to 1300L), state.toDraft()?.rates)
    }

    @Test
    fun `rate for a procedure missing from the catalog is not in the draft`() {
        val state = PlaceEditorState.forExisting(existing(), decimalSeparator = ',')
        state.updateCatalog(listOf(exam))

        assertEquals(mapOf(exam.id to 1250L), state.toDraft()?.rates)
    }

    @Test
    fun `name and address input is capped at the shared limits`() {
        val state = newState()
        state.updateName("а".repeat(CatalogLimits.MAX_NAME_LENGTH + 50))
        state.updateAddress("x".repeat(CatalogLimits.MAX_ADDRESS_LENGTH + 50))
        assertEquals(CatalogLimits.MAX_NAME_LENGTH, state.name.length)
        assertEquals(CatalogLimits.MAX_ADDRESS_LENGTH, state.address.length)
        assertNotNull(state.toDraft())
    }
}
