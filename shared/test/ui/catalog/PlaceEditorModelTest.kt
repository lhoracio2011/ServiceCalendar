package app.servicecalendar.ui.catalog

import app.servicecalendar.data.CatalogSnapshot
import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.data.PlaceWithRates
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.model.Place
import app.servicecalendar.model.PlaceRate
import app.servicecalendar.model.Procedure
import app.servicecalendar.money.MinorAmount
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * How the place editor reacts to a backup restore: the [PlaceEditorModel] logic without a database, Compose
 * or `Dispatchers.Main`. Catalogs with a generation arrive from a fake snapshot flow.
 */
class PlaceEditorModelTest {
    private val exam = Procedure(1, "Огляд")
    private val cleaning = Procedure(2, "Чистка")

    private fun place(id: Long, name: String, rate: Long) =
        PlaceWithRates(Place(id, name, null, 0xFF22B14CL), listOf(PlaceRate(id, exam.id, MinorAmount(rate))))

    /** The local dataset (generation 1): place 1 "Стара клініка" priced 10.00. */
    private val local = CatalogSnapshot(1, listOf(exam), listOf(place(1, "Стара клініка", 1000)))

    /** The restored dataset (generation 2): place 1 is "Клініка А" priced 12.50, plus the procedure "Чистка". */
    private val restored = CatalogSnapshot(2, listOf(exam, cleaning), listOf(place(1, "Клініка А", 1250)))

    private class Fake(initial: CatalogSnapshot) {
        val snapshots = MutableStateFlow(initial)
        val generation = MutableStateFlow<Long?>(initial.generation)
        val restoring = MutableStateFlow(false)
        val saves = mutableListOf<Pair<PlaceDraft, Long>>()
        val removals = mutableListOf<Pair<Long, Long>>()

        private fun check(opened: Long) {
            if (opened != generation.value) throw StaleGeneration()
        }

        suspend fun save(draft: PlaceDraft, opened: Long) {
            saves += draft to opened
            check(opened)
        }

        suspend fun remove(id: Long, opened: Long) {
            removals += id to opened
            check(opened)
        }

        /** A successful restore: commits the new dataset and generation, then emits the catalogs again. */
        fun commitRestore(set: CatalogSnapshot) {
            restoring.value = true
            generation.value = set.generation
            snapshots.value = set
            restoring.value = false
        }
    }

    private fun modelTest(block: suspend CoroutineScope.(scope: CoroutineScope, fake: Fake) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            withTimeout(30_000) { block(scope, Fake(local)) }
        } finally {
            scope.cancel()
        }
    }

    private fun model(scope: CoroutineScope, fake: Fake, placeId: Long? = 1) = PlaceEditorModel(
        placeId = placeId,
        scope = scope,
        snapshots = fake.snapshots,
        savePlace = fake::save,
        removePlace = fake::remove,
        generation = fake.generation,
        restoring = fake.restoring,
        random = Random(1),
        decimalSeparator = ',',
    )

    private suspend fun awaitUntil(message: String, condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
        assertTrue(condition(), message)
    }

    private suspend fun settle() = repeat(20) { yield() }

    private suspend fun PlaceEditorModel.loaded(): PlaceEditorState {
        awaitUntil("editor did not load") { state != null }
        return checkNotNull(state)
    }

    @Test
    fun `restore reopens an existing place from restored data and drops the draft`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        val opened = model.loaded()
        assertEquals("Стара клініка", opened.name)
        opened.updateName("Чернетка")
        opened.updatePrice(exam.id, "99")

        fake.commitRestore(restored)
        awaitUntil("editor did not reopen") { model.state.let { it != null && it !== opened } }

        val reopened = checkNotNull(model.state)
        assertEquals("Клініка А", reopened.name)
        assertEquals("12,50", reopened.priceText(exam.id))
        assertEquals(listOf(exam, cleaning), reopened.catalog)
        assertTrue(model.restoredNotice)
        assertFalse(model.staleData)

        model.dismissRestoredNotice()
        assertFalse(model.restoredNotice)
        model.save()
        awaitUntil("place was not saved") { model.closed }
        assertEquals(2L, fake.saves.single().second)
        assertEquals("Клініка А", fake.saves.single().first.name)
    }

    @Test
    fun `place missing after restore shows a message instead of the form`() = modelTest { scope, fake ->
        val model = model(scope, fake, placeId = 1)
        model.loaded().updateName("Чернетка")

        fake.commitRestore(CatalogSnapshot(2, listOf(exam), listOf(place(7, "Інше місце", 100))))
        awaitUntil("editor was not replaced by the message") { model.loadError != null }

        assertNull(model.state)
        assertEquals(CatalogStrings.PLACE_GONE_AFTER_RESTORE, model.loadError)
        model.save()
        model.remove()
        settle()
        assertTrue(fake.saves.isEmpty())
        assertTrue(fake.removals.isEmpty())
    }

    @Test
    fun `new place draft is reset after restore`() = modelTest { scope, fake ->
        val model = model(scope, fake, placeId = null)
        val opened = model.loaded()
        opened.updateName("Нове")
        opened.toggleProcedure(exam.id, true)
        opened.updatePrice(exam.id, "5")

        fake.commitRestore(restored)
        awaitUntil("draft was not reset") { model.state.let { it != null && it !== opened } }

        val reopened = checkNotNull(model.state)
        assertTrue(reopened.isNew)
        assertEquals("", reopened.name)
        assertFalse(reopened.isSelected(exam.id))
        assertEquals(listOf(exam, cleaning), reopened.catalog)
        assertTrue(model.restoredNotice)
    }

    @Test
    fun `save and remove pass the opened generation and stale rejection asks to reload`() =
        modelTest { scope, fake ->
            val model = model(scope, fake)
            val opened = model.loaded()
            opened.updateName("Змінена")

            // The commit happened but the editor has not reacted yet, so the save carries the old generation.
            fake.generation.value = 2
            fake.snapshots.value = restored
            model.save()
            awaitUntil("stale data dialog was not shown") { model.staleData }
            assertEquals(1L, fake.saves.single().second)
            assertFalse(model.closed)

            // No new attempts while the dialog is open.
            model.save()
            model.remove()
            settle()
            assertEquals(1, fake.saves.size)
            assertTrue(fake.removals.isEmpty())

            model.reload()
            awaitUntil("editor did not reopen") { model.state.let { it != null && it !== opened } }
            assertFalse(model.staleData)
            assertEquals("Клініка А", checkNotNull(model.state).name)

            model.remove()
            awaitUntil("place was not removed") { model.closed }
            assertEquals(listOf(1L to 2L), fake.removals)
        }

    @Test
    fun `editing is blocked while restoring and a failed restore keeps the draft`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        val opened = model.loaded()
        opened.updateName("Чернетка")

        fake.restoring.value = true
        awaitUntil("editor was not blocked") { model.blocked }
        model.save()
        model.remove()
        settle()
        assertTrue(fake.saves.isEmpty())
        assertTrue(fake.removals.isEmpty())

        fake.restoring.value = false
        awaitUntil("block was not released") { !model.blocked }
        settle()
        assertSame(opened, model.state)
        assertEquals("Чернетка", opened.name)
        assertFalse(model.restoredNotice)

        model.save()
        awaitUntil("place was not saved") { model.closed }
        assertEquals("Чернетка", fake.saves.single().first.name)
        assertEquals(1L, fake.saves.single().second)
    }

    @Test
    fun `catalog updates of the same generation refresh procedures and a newer one reopens`() =
        modelTest { scope, fake ->
            val model = model(scope, fake)
            val opened = model.loaded()
            opened.updateName("Чернетка")
            val fresh = scope.launch { model.keepCatalogFresh() }
            try {
                // A new procedure in the same dataset: the draft stays.
                fake.snapshots.value = local.copy(procedures = listOf(exam, cleaning))
                awaitUntil("catalog did not refresh") { opened.catalog == listOf(exam, cleaning) }
                assertSame(opened, model.state)
                assertEquals("Чернетка", opened.name)

                // A snapshot of a newer generation (a restore) reopens the editor.
                fake.generation.value = 2
                fake.snapshots.value = restored
                awaitUntil("editor did not reopen") { model.state.let { it != null && it !== opened } }
                assertEquals("Клініка А", checkNotNull(model.state).name)
                assertTrue(model.restoredNotice)
                assertNotSame(opened, model.state)
            } finally {
                fresh.cancel()
            }
        }
}
