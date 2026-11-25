package app.servicecalendar.domain

import app.servicecalendar.data.PlaceDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CatalogValidationTest {
    @Test
    fun `name is trimmed and blank is rejected`() {
        assertEquals("Огляд", validateCatalogName("  Огляд \n"))
        for (blank in listOf("", "   ", "\n\t")) {
            val e = assertFailsWith<ValidationError> { validateCatalogName(blank) }
            assertEquals("name", e.field)
        }
    }

    @Test
    fun `rate must not be negative`() {
        assertEquals(0L, validateRate(0).minor)
        assertEquals(1250L, validateRate(1250).minor)
        val e = assertFailsWith<ValidationError> { validateRate(-1, "rate.7") }
        assertEquals("rate.7", e.field)
    }

    @Test
    fun `blank address becomes null and color must fit ARGB`() {
        assertNull(validatePlaceAddress("   "))
        assertNull(validatePlaceAddress(null))
        assertEquals("вул. Лісова, 5", validatePlaceAddress(" вул. Лісова, 5 "))
        assertEquals(0xFF2E7D32L, validatePlaceColor(0xFF2E7D32L))
        assertFailsWith<ValidationError> { validatePlaceColor(-1) }
        assertFailsWith<ValidationError> { validatePlaceColor(0x1_0000_0000L) }
    }

    @Test
    fun `place draft is normalized and rate errors name the procedure`() {
        val ok = validatePlaceDraft(PlaceDraft(null, " Клініка ", " ", 0xFF2E7D32L, mapOf(1L to 1250L)))
        assertEquals("Клініка", ok.name)
        assertNull(ok.address)
        assertEquals(1250L, ok.rates.getValue(1L).minor)

        val e = assertFailsWith<ValidationError> {
            validatePlaceDraft(PlaceDraft(null, "Клініка", null, 0xFF2E7D32L, mapOf(3L to -5L)))
        }
        assertEquals("rate.3", e.field)
    }

    @Test
    fun `name and address length limits use the shared constants`() {
        val maxName = "а".repeat(CatalogLimits.MAX_NAME_LENGTH)
        assertEquals(maxName, validateCatalogName(" $maxName "))
        val nameError = assertFailsWith<ValidationError> { validateCatalogName(maxName + "а", "name") }
        assertEquals("name", nameError.field)
        assertEquals(nameTooLongReason(), nameError.reason)

        val maxAddress = "x".repeat(CatalogLimits.MAX_ADDRESS_LENGTH)
        assertEquals(maxAddress, validatePlaceAddress(maxAddress))
        val addressError = assertFailsWith<ValidationError> { validatePlaceAddress(maxAddress + "x") }
        assertEquals("address", addressError.field)
        assertEquals(addressTooLongReason(), addressError.reason)
    }
}
