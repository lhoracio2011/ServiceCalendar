package app.servicecalendar.ui.calendar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.ui.entry.CountInput
import app.servicecalendar.ui.entry.EntryStrings
import app.servicecalendar.ui.entry.parseCountInput
import app.servicecalendar.ui.entry.toEntryMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** The open "Scheduled procedures (A)" dialog: which day and place, the value shown first, and the generation it was opened at. */
internal class ScheduledEdit(val date: LocalDate, val placeId: Long, val initial: Int?, val generation: Long)

/**
 * Quick edit of A from a day card. The data generation is captured when the dialog opens, so a backup restore
 * in the meantime makes the write fail as stale instead of overwriting restored data with an old number.
 */
internal class ScheduledEditModel(
    private val scope: CoroutineScope,
    private val generation: StateFlow<Long?>,
    private val write: suspend (date: LocalDate, placeId: Long, value: Int?, openedGeneration: Long) -> Unit,
) {
    var editing: ScheduledEdit? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    var saving: Boolean by mutableStateOf(false)
        private set

    /** Set when the data changed (for example after a restore) between opening the dialog and saving. */
    var staleNotice: Boolean by mutableStateOf(false)
        private set

    fun begin(date: LocalDate, placeId: Long, current: Int?) {
        val opened = generation.value ?: return
        error = null
        editing = ScheduledEdit(date, placeId, current, opened)
    }

    fun cancel() {
        if (saving) return
        editing = null
        error = null
    }

    fun clearError() {
        error = null
    }

    fun dismissStaleNotice() {
        staleNotice = false
    }

    fun save(text: String) {
        val edit = editing ?: return
        if (saving) return
        val value = when (val parsed = parseCountInput(text)) {
            CountInput.Empty -> null
            is CountInput.Ok -> parsed.value
            is CountInput.Invalid -> {
                error = parsed.message
                return
            }
        }
        saving = true
        scope.launch {
            try {
                write(edit.date, edit.placeId, value, edit.generation)
                editing = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleGeneration) {
                editing = null
                staleNotice = true
            } catch (e: ValidationError) {
                error = EntryStrings.ERROR_SCHEDULED_REJECTED
            } catch (e: Exception) {
                error = e.toEntryMessage()
            } finally {
                saving = false
            }
        }
    }
}
