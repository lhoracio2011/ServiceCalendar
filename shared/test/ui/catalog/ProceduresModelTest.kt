package app.servicecalendar.ui.catalog

import app.servicecalendar.data.CatalogSnapshot
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.model.Procedure
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * How the procedures screen reacts to a backup restore: the [ProceduresModel] logic without a database,
 * Compose or `Dispatchers.Main`. Dialogs remember the generation of the list they were opened from.
 */
class ProceduresModelTest {
    private val exam = Procedure(1, "Огляд")
    private val dressing = Procedure(2, "Перев'язка")
    private val local = CatalogSnapshot(1, listOf(Procedure(1, "Стара")), emptyList())
    private val restored = CatalogSnapshot(2, listOf(exam, dressing), emptyList())

    private class Fake(initial: CatalogSnapshot) {
        val snapshots = MutableStateFlow(initial)
        val generation = MutableStateFlow<Long?>(initial.generation)
        val restoring = MutableStateFlow(false)
        val calls = mutableListOf<String>()

        private fun check(opened: Long) {
            if (opened != generation.value) throw StaleGeneration()
        }

        suspend fun create(name: String, opened: Long) {
            calls += "create $name @$opened"
            check(opened)
        }

        suspend fun rename(id: Long, name: String, opened: Long) {
            calls += "rename $id $name @$opened"
            check(opened)
        }

        suspend fun remove(id: Long, opened: Long) {
            calls += "remove $id @$opened"
            check(opened)
        }

        fun commitRestore(set: CatalogSnapshot) {
            restoring.value = true
            generation.value = set.generation
            snapshots.value = set
            restoring.value = false
        }
    }

    private fun modelTest(block: suspend CoroutineScope.(model: ProceduresModel, fake: Fake) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            val fake = Fake(local)
            val model = ProceduresModel(
                scope = scope,
                snapshots = fake.snapshots,
                createProcedure = fake::create,
                renameProcedure = fake::rename,
                removeProcedure = fake::remove,
                generation = fake.generation,
                restoring = fake.restoring,
            )
            // The screen collects the listing while it is visible.
            val screen = scope.launch { model.listing.collect {} }
            withTimeout(30_000) { block(model, fake) }
            screen.cancel()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitUntil(message: String, condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
        assertTrue(condition(), message)
    }

    private suspend fun settle() = repeat(20) { yield() }

    private suspend fun ProceduresModel.listed(generation: Long): ProcedureListing {
        awaitUntil("listing of generation $generation did not appear") { listing.value?.generation == generation }
        return checkNotNull(listing.value)
    }

    @Test
    fun `dialogs carry the listed generation into repository calls`() = modelTest { model, fake ->
        val listing = model.listed(1)
        assertEquals(listOf(Procedure(1, "Стара")), listing.procedures)

        model.updateAddName("Нова")
        model.submitAdd(listing.generation)
        awaitUntil("name field was not cleared") { model.addName.isEmpty() }

        model.startRename(listing.procedures.single(), listing.generation)
        model.updateDialogName("Інша")
        model.submitDialog()
        awaitUntil("dialog was not closed") { model.dialog == null }

        model.askRemove(listing.procedures.single(), listing.generation)
        model.confirmRemoval()
        awaitUntil("removal was not performed") { fake.calls.size == 3 }
        assertEquals(listOf("create Нова @1", "rename 1 Інша @1", "remove 1 @1"), fake.calls)
        assertFalse(model.staleData)
    }

    @Test
    fun `restore closes open dialogs and refreshes the list with a notice`() = modelTest { model, fake ->
        val listing = model.listed(1)
        model.startRename(listing.procedures.single(), listing.generation)
        model.updateDialogName("Чернетка")

        fake.commitRestore(restored)
        awaitUntil("dialog was not closed") { model.dialog == null }
        assertTrue(model.restoredNotice)
        assertEquals(listOf(exam, dressing), model.listed(2).procedures)
        assertTrue(fake.calls.isEmpty())

        model.dismissRestoredNotice()
        assertFalse(model.restoredNotice)
        model.askRemove(dressing, 2)
        model.confirmRemoval()
        awaitUntil("removal was not performed") { fake.calls.isNotEmpty() }
        assertEquals(listOf("remove 2 @2"), fake.calls)
    }

    @Test
    fun `restore also drops a pending removal and without open dialogs shows no notice`() =
        modelTest { model, fake ->
            val listing = model.listed(1)
            model.askRemove(listing.procedures.single(), listing.generation)
            fake.commitRestore(restored)
            awaitUntil("removal confirmation was not reset") { model.pendingRemoval == null }
            assertTrue(model.restoredNotice)
            model.dismissRestoredNotice()

            fake.commitRestore(restored.copy(generation = 3))
            model.listed(3)
            settle()
            assertFalse(model.restoredNotice)
            assertTrue(fake.calls.isEmpty())
        }

    @Test
    fun `stale rejection shows the reload dialog`() = modelTest { model, fake ->
        val listing = model.listed(1)
        model.startRename(listing.procedures.single(), listing.generation)
        model.updateDialogName("Пізня")

        // The commit has happened but the screen has not reacted yet.
        fake.generation.value = 2
        model.submitDialog()
        awaitUntil("stale data dialog was not shown") { model.staleData }
        assertEquals(listOf("rename 1 Пізня @1"), fake.calls)

        // New actions are ignored while the dialog is open.
        model.updateAddName("Нова")
        model.submitAdd(2)
        settle()
        assertEquals(1, fake.calls.size)

        model.reload()
        assertFalse(model.staleData)
        assertNull(model.dialog)
        assertNull(model.pendingRemoval)
    }

    @Test
    fun `stale removal shows the reload dialog`() = modelTest { model, fake ->
        val listing = model.listed(1)
        model.askRemove(listing.procedures.single(), listing.generation)
        fake.generation.value = 2
        model.confirmRemoval()
        awaitUntil("stale data dialog was not shown") { model.staleData }
        assertEquals(listOf("remove 1 @1"), fake.calls)
        assertNull(model.message)
    }

    @Test
    fun `blank add name is a field error without a repository call`() = modelTest { model, fake ->
        val listing = model.listed(1)
        model.updateAddName("   ")
        model.submitAdd(listing.generation)
        assertEquals(CatalogStrings.ERROR_NAME_REQUIRED, model.addError)
        model.updateAddName("Нова")
        assertNull(model.addError)
        settle()
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `actions are blocked while restoring and a failed restore keeps the dialog`() = modelTest { model, fake ->
        val listing = model.listed(1)
        model.startRename(listing.procedures.single(), listing.generation)
        model.updateDialogName("Чернетка")

        fake.restoring.value = true
        awaitUntil("screen was not blocked") { model.blocked }
        model.submitDialog()
        settle()
        assertTrue(fake.calls.isEmpty())
        val kept = assertNotNull(model.dialog)
        assertFalse(kept.saving)

        // New dialogs and additions are ignored during a restore.
        model.dismissDialog()
        model.startRename(listing.procedures.single(), listing.generation)
        model.updateAddName("Нова")
        model.submitAdd(listing.generation)
        model.askRemove(listing.procedures.single(), listing.generation)
        settle()
        assertNull(model.dialog)
        assertNull(model.pendingRemoval)
        assertTrue(fake.calls.isEmpty())

        // Failure before the commit: same generation, editing is available again.
        fake.restoring.value = false
        awaitUntil("block was not released") { !model.blocked }
        model.startRename(listing.procedures.single(), listing.generation)
        model.updateDialogName("Чернетка")
        fake.restoring.value = true
        awaitUntil("screen was not blocked") { model.blocked }
        fake.restoring.value = false
        awaitUntil("block was not released") { !model.blocked }
        settle()
        assertEquals("Чернетка", model.dialog?.name)
        assertFalse(model.restoredNotice)

        model.submitDialog()
        awaitUntil("dialog was not closed") { model.dialog == null }
        assertEquals(listOf("rename 1 Чернетка @1"), fake.calls)
    }
}
