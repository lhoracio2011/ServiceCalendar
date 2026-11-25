package app.servicecalendar.ui.calendar

import app.servicecalendar.domain.StaleGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate

/** The quick A edit on a day card, without a database or Compose. */
class ScheduledEditModelTest {
    private val date = LocalDate(2026, 11, 18)

    private class Write(val date: LocalDate, val placeId: Long, val value: Int?, val generation: Long)

    private fun test(block: suspend (ScheduledEditModel, MutableStateFlow<Long?>, MutableList<Write>) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val generation = MutableStateFlow<Long?>(3L)
        val writes = mutableListOf<Write>()
        val model = ScheduledEditModel(scope, generation) { d, p, v, g ->
            writes += Write(d, p, v, g)
            if (g != generation.value) throw StaleGeneration()
        }
        try {
            withTimeout(30_000) { block(model, generation, writes) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
    }

    @Test
    fun `saves a number with the generation captured on open and closes`() = test { model, _, writes ->
        model.begin(date, 5, 110)
        assertEquals(110, model.editing?.initial)
        model.save("67")
        until { model.editing == null }
        assertEquals(1, writes.size)
        assertEquals(67, writes[0].value)
        assertEquals(5L, writes[0].placeId)
        assertEquals(date, writes[0].date)
        assertEquals(3L, writes[0].generation)
    }

    @Test
    fun `blank input clears A`() = test { model, _, writes ->
        model.begin(date, 5, 110)
        model.save("  ")
        until { model.editing == null }
        assertNull(writes.single().value)
    }

    @Test
    fun `invalid input shows an error and writes nothing`() = test { model, _, writes ->
        for (text in listOf("-3", "1.5", "x", "99999999999")) {
            model.begin(date, 5, null)
            model.save(text)
            assertNotNull(model.error, text)
            assertNotNull(model.editing, text)
            model.clearError()
            assertNull(model.error)
        }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `restore between open and save gives a stale notice and no overwrite`() = test { model, generation, writes ->
        model.begin(date, 5, 10)
        generation.value = 4L
        model.save("12")
        until { model.staleNotice }
        assertNull(model.editing)
        assertFalse(model.saving)
        assertEquals(3L, writes.single().generation)
        model.dismissStaleNotice()
        assertFalse(model.staleNotice)
    }

    @Test
    fun `cancel closes without writing`() = test { model, _, writes ->
        model.begin(date, 5, 10)
        model.cancel()
        assertNull(model.editing)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `cannot open before the data generation is known`() = test { model, generation, _ ->
        generation.value = null
        model.begin(date, 5, 10)
        assertNull(model.editing)
    }
}
