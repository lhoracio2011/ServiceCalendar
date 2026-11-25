package app.servicecalendar.data

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

/**
 * Test dispatcher for the data store: while [holding] is true, dispatched blocks are queued instead of
 * run. This lets a test park a [DataStore.write] call after it has passed the mode check but before it
 * reaches the `Mutex`. Otherwise blocks run on `Dispatchers.Default`.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class GatedDispatcher : CoroutineDispatcher() {
    private val hold = AtomicBoolean(false)

    var holding: Boolean
        get() = hold.load()
        set(value) = hold.store(value)
    private val held = AtomicReference<List<Pair<CoroutineContext, Runnable>>>(emptyList())

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (hold.load()) {
            while (true) {
                val current = held.load()
                if (held.compareAndSet(current, current + (context to block))) return
            }
        }
        Dispatchers.Default.dispatch(context, block)
    }

    fun heldCount(): Int = held.load().size

    /** Dispatches the held blocks to `Dispatchers.Default` in arrival order. */
    fun releaseHeld() {
        held.exchange(emptyList()).forEach { (context, block) -> Dispatchers.Default.dispatch(context, block) }
    }
}
