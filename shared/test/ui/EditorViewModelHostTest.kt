package app.servicecalendar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Closing an editor clears its ViewModels; without closing (e.g. rotation) they are kept. */
class EditorViewModelHostTest {
    private class Probe : ViewModel() {
        var cleared = 0

        override fun onCleared() {
            cleared++
        }
    }

    private fun ViewModelProvider.probe(key: String): Probe = this[key, Probe::class]

    private fun provider(host: EditorViewModelHost) = ViewModelProvider.create(
        store = host.viewModelStore,
        factory = viewModelFactory { initializer { Probe() } },
    )

    @Test
    fun `close clears nested view models and the next open gets a fresh one`() {
        val host = EditorViewModelHost()
        val first = provider(host).probe("day-entry")
        assertSame(first, provider(host).probe("day-entry"))

        host.close()
        assertEquals(1, first.cleared)
        assertNotSame(first, provider(host).probe("day-entry"))
    }

    @Test
    fun `clearing the host itself clears nested view models`() {
        val host = EditorViewModelHost()
        val probe = provider(host).probe("place-editor")
        host.viewModelStore.clear()
        assertEquals(1, probe.cleared)
    }
}
