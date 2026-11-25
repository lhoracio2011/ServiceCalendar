package app.servicecalendar.ui.entry

import app.servicecalendar.data.DayEntryEditorData
import app.servicecalendar.data.DayEntryEditorRow
import app.servicecalendar.data.ScheduledChange
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate

/**
 * How the day editor reacts to a backup restore: the [DayEntryModel] logic without a database, Compose or
 * `Dispatchers.Main`. The store generation and restore mode are supplied as flows, and `loadEditor`/`save`
 * by a fake that, like the repository, rejects a save made with an old generation.
 */
class DayEntryModelTest {
    private val date = LocalDate(2026, 11, 1)
    private val place = Place(id = 1, name = "A", address = null, color = 0xFF22B14CL)
    private val exam = 1L

    /** Generation 1 has "Огляд" x4; the restored dataset (generation 2) has x3. */
    private fun dataFor(generation: Long) = DayEntryEditorData(
        date = date,
        place = place,
        generation = generation,
        entryId = 1,
        rows = listOf(
            DayEntryEditorRow(
                procedureId = exam,
                procedureName = "Огляд",
                procedureArchived = false,
                currentRate = MinorAmount(1250),
                savedQuantity = if (generation == 1L) 4 else 3,
                savedAppliedAmount = MinorAmount(1250),
            ),
        ),
        otherPlacesTotal = MinorAmount(0),
    )

    private class Fake(private val dataFor: (Long) -> DayEntryEditorData) {
        val generation = MutableStateFlow<Long?>(1L)
        val restoring = MutableStateFlow(false)
        var loads = 0
        val saves = mutableListOf<Pair<Map<Long, Int>, Long>>()
        val scheduledSaves = mutableListOf<ScheduledChange>()

        /** When set, a save waits for it (simulates a write queued in the store). */
        var saveGate: CompletableDeferred<Unit>? = null

        /** The generation the next read returns (the current one by default). */
        var nextLoadGeneration: Long? = null

        suspend fun load(): DayEntryEditorData {
            loads++
            val g = nextLoadGeneration ?: checkNotNull(generation.value)
            nextLoadGeneration = null
            return dataFor(g)
        }

        suspend fun save(quantities: Map<Long, Int>, scheduled: ScheduledChange, opened: Long) {
            saves += quantities to opened
            scheduledSaves += scheduled
            saveGate?.await()
            if (opened != generation.value) throw StaleGeneration()
        }

        /** A successful restore: enter the mode, commit the new generation, leave the mode. */
        fun commitRestore(newGeneration: Long) {
            restoring.value = true
            generation.value = newGeneration
            restoring.value = false
        }
    }

    private fun modelTest(block: suspend CoroutineScope.(scope: CoroutineScope, fake: Fake) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            withTimeout(30_000) { block(scope, Fake(::dataFor)) }
        } finally {
            scope.cancel()
        }
    }

    private fun model(scope: CoroutineScope, fake: Fake) = DayEntryModel(
        scope = scope,
        load = fake::load,
        save = fake::save,
        generation = fake.generation,
        restoring = fake.restoring,
    )

    private suspend fun awaitUntil(message: String, condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
        assertTrue(condition(), message)
    }

    /** Lets the model's coroutines run everything already scheduled. */
    private suspend fun settle() = repeat(20) { yield() }

    private suspend fun DayEntryModel.loaded(): DayEntryEditorState {
        awaitUntil("editor did not load") { state != null }
        return checkNotNull(state)
    }

    @Test
    fun `restore commit discards the draft and reopens the editor from restored data`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        val opened = model.loaded()
        assertEquals(1L, opened.generation)
        opened.updateQuantity(exam, "9")

        fake.commitRestore(2)
        awaitUntil("editor did not reopen") { model.state?.generation == 2L }

        val reopened = checkNotNull(model.state)
        assertNotSame(opened, reopened)
        assertEquals("3", reopened.text(exam))
        assertFalse(reopened.isDirty)
        assertTrue(model.restoredNotice)
        assertFalse(model.staleData)
        assertFalse(model.blocked)
        assertTrue(fake.saves.isEmpty())

        model.dismissRestoredNotice()
        assertFalse(model.restoredNotice)
        // Editing the restored data works again and saves with the new generation.
        reopened.updateQuantity(exam, "5")
        model.save()
        awaitUntil("save did not finish") { model.closed }
        assertEquals(listOf(mapOf(exam to 5) to 2L), fake.saves)
    }

    @Test
    fun `editing is blocked while restoring and a failed restore keeps the draft`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        val opened = model.loaded()
        opened.updateQuantity(exam, "9")

        fake.restoring.value = true
        awaitUntil("editor was not blocked") { model.blocked }
        model.save()
        settle()
        assertTrue(fake.saves.isEmpty())
        assertFalse(model.saving)

        // Failure before the commit: same generation, the draft and state are unchanged.
        fake.restoring.value = false
        awaitUntil("block was not released") { !model.blocked }
        settle()
        assertSame(opened, model.state)
        assertEquals("9", opened.text(exam))
        assertFalse(model.restoredNotice)
        assertEquals(1, fake.loads)

        model.save()
        awaitUntil("save did not finish") { model.closed }
        assertEquals(listOf(mapOf(exam to 9) to 1L), fake.saves)
    }

    @Test
    fun `save fired after the restore committed is rejected with the reload dialog`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        val opened = model.loaded()
        opened.updateQuantity(exam, "9")

        // The commit has happened but the editor has not reacted yet, so the save uses the old generation.
        fake.generation.value = 2
        model.save()
        awaitUntil("stale data dialog was not shown") { model.staleData }

        assertEquals(listOf(mapOf(exam to 9) to 1L), fake.saves)
        assertFalse(model.closed)
        assertFalse(model.saving)
        settle()
        // The "data changed" dialog is not overridden by an automatic reopen.
        assertTrue(model.staleData)
        assertEquals(1, fake.loads)

        model.reload()
        awaitUntil("editor did not reopen") { model.state?.generation == 2L }
        assertFalse(model.staleData)
        assertEquals("3", checkNotNull(model.state).text(exam))
    }

    @Test
    fun `save queued behind the restore is rejected and the editor reopens with restored data`() =
        modelTest { scope, fake ->
            val model = model(scope, fake)
            val opened = model.loaded()
            opened.updateQuantity(exam, "9")

            val gate = CompletableDeferred<Unit>()
            fake.saveGate = gate
            model.save()
            awaitUntil("save did not start") { fake.saves.isNotEmpty() }
            assertTrue(model.saving)

            // The restore commits while the save waits in the queue: reopening waits for the save result.
            fake.commitRestore(2)
            settle()
            assertSame(opened, model.state)
            gate.complete(Unit)

            awaitUntil("rejection was not shown") { model.staleData }
            assertFalse(model.closed)
            model.reload()
            awaitUntil("editor did not reopen") { model.state?.generation == 2L }
            assertEquals("3", checkNotNull(model.state).text(exam))
        }

    @Test
    fun `save finished before the restore closes the editor and the new generation is ignored`() =
        modelTest { scope, fake ->
            val model = model(scope, fake)
            model.loaded().updateQuantity(exam, "9")
            model.save()
            awaitUntil("save did not finish") { model.closed }

            fake.commitRestore(2)
            settle()
            assertEquals(1, fake.loads)
            assertFalse(model.restoredNotice)
        }

    @Test
    fun `restore committed during loading reopens once more from the restored set`() = modelTest { scope, fake ->
        // The read saw the old dataset while the store had already published the new generation.
        fake.nextLoadGeneration = 1
        fake.generation.value = 2
        val model = model(scope, fake)

        awaitUntil("editor did not reopen") { model.state?.generation == 2L }
        assertEquals(2, fake.loads)
        assertTrue(model.restoredNotice)
    }

    @Test
    fun `restore while the stale dialog is open leaves the decision to the user`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        val opened = model.loaded()
        opened.updateQuantity(exam, "9")
        fake.generation.value = 2
        model.save()
        awaitUntil("stale data dialog was not shown") { model.staleData }

        fake.commitRestore(3)
        settle()
        assertTrue(model.staleData)
        assertSame(opened, model.state)

        model.reload()
        awaitUntil("editor did not reopen") { model.state?.generation == 3L }
        assertNull(model.loadError)
        assertNotNull(model.state)
    }

    @Test
    fun `save passes the typed scheduled count as Set`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        val state = model.loaded()
        state.updateScheduled("12")
        model.save()
        awaitUntil("save did not finish") { model.closed }
        assertEquals(listOf<ScheduledChange>(ScheduledChange.Set(12)), fake.scheduledSaves)
    }

    @Test
    fun `save with blank scheduled clears it`() = modelTest { scope, fake ->
        val model = model(scope, fake)
        model.loaded()
        model.save()
        awaitUntil("save did not finish") { model.closed }
        assertEquals(listOf<ScheduledChange>(ScheduledChange.Set(null)), fake.scheduledSaves)
    }
}
