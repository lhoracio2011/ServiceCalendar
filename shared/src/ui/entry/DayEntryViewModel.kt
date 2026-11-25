package app.servicecalendar.ui.entry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.servicecalendar.AppContainer
import app.servicecalendar.data.DataStore
import app.servicecalendar.data.DayEntryEditorData
import app.servicecalendar.data.ScheduledChange
import app.servicecalendar.data.WorkEntryRepository
import app.servicecalendar.domain.DomainException
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

internal class DayEntryModel(
    private val scope: CoroutineScope,
    load: suspend () -> DayEntryEditorData,
    save: suspend (quantities: Map<Long, Int>, scheduled: ScheduledChange, openedGeneration: Long) -> Unit,
    // While a restore runs the editor is blocked. On a new generation it drops the draft and reopens from restored data; a late save with the old generation is rejected as stale.
    private val generation: StateFlow<Long?>,
    private val restoring: StateFlow<Boolean>,
) {
    private val readEditor = load
    private val writeEntry = save

    var state: DayEntryEditorState? by mutableStateOf(null)
        private set

    var loadError: String? by mutableStateOf(null)
        private set

    var saving: Boolean by mutableStateOf(false)
        private set

    var staleData: Boolean by mutableStateOf(false)
        private set

    var closed: Boolean by mutableStateOf(false)
        private set

    var blocked: Boolean by mutableStateOf(restoring.value)
        private set

    var restoredNotice: Boolean by mutableStateOf(false)
        private set

    private var loadJob: Job? = null

    private var reopenAfterSave = false

    init {
        startLoading()
        scope.launch { restoring.collect { blocked = it } }
        scope.launch { generation.collect { onGeneration(it) } }
    }

    fun reload() {
        if (saving) return
        startLoading()
    }

    fun dismissRestoredNotice() {
        restoredNotice = false
    }

    fun save() {
        val current = state ?: return
        if (saving || staleData || closed || blocked || restoring.value) return
        val quantities = current.toQuantities() ?: return
        saving = true
        scope.launch {
            val outcome = try {
                writeEntry(quantities, ScheduledChange.Set(current.scheduled), current.generation)
                SaveOutcome.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleGeneration) {
                SaveOutcome.Stale
            } catch (e: ValidationError) {
                current.applyError(e)
                SaveOutcome.Failed
            } catch (e: Exception) {
                current.showGeneralError(e.toEntryMessage())
                SaveOutcome.Failed
            } finally {
                saving = false
            }
            val generationChanged = reopenAfterSave || generation.value.let { it != null && it != current.generation }
            reopenAfterSave = false
            when {
                outcome == SaveOutcome.Saved -> closed = true
                outcome == SaveOutcome.Stale -> staleData = true
                generationChanged -> reopenAfterRestore()
            }
        }
    }

    private enum class SaveOutcome { Saved, Stale, Failed }

    private fun onGeneration(value: Long?) {
        if (value == null || closed) return
        val opened = state?.generation ?: return
        if (value == opened) return
        when {
            saving -> reopenAfterSave = true
            staleData -> Unit
            else -> reopenAfterRestore()
        }
    }

    private fun reopenAfterRestore() {
        restoredNotice = true
        startLoading()
    }

    private fun startLoading() {
        loadJob?.cancel()
        state = null
        loadError = null
        staleData = false
        loadJob = scope.launch {
            try {
                val loaded = DayEntryEditorState(readEditor())
                state = loaded
                val latest = generation.value
                if (latest != null && latest != loaded.generation) reopenAfterRestore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ValidationError) {
                loadError = EntryStrings.PLACE_NOT_FOUND
            } catch (e: Exception) {
                loadError = e.toEntryMessage()
            }
        }
    }
}

class DayEntryViewModel(
    private val date: LocalDate,
    private val placeId: Long,
    private val repository: WorkEntryRepository = AppContainer.instance.workEntryRepository,
    store: DataStore = AppContainer.instance.dataStore,
) : ViewModel() {
    private val model = DayEntryModel(
        scope = viewModelScope,
        load = { repository.loadEditor(date, placeId) },
        save = { quantities, scheduled, generation -> repository.save(date, placeId, quantities, generation, scheduled) },
        generation = store.dataGeneration,
        restoring = store.isRestoring,
    )

    val state: DayEntryEditorState? get() = model.state

    val loadError: String? get() = model.loadError

    val saving: Boolean get() = model.saving

    val staleData: Boolean get() = model.staleData

    val closed: Boolean get() = model.closed

    val blocked: Boolean get() = model.blocked

    val restoredNotice: Boolean get() = model.restoredNotice

    fun reload() = model.reload()

    fun save() = model.save()

    fun dismissRestoredNotice() = model.dismissRestoredNotice()
}

internal fun Exception.toEntryMessage(): String =
    (this as? DomainException)?.toUserMessage() ?: EntryStrings.UNEXPECTED_ERROR
