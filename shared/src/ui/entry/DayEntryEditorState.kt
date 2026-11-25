package app.servicecalendar.ui.entry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.servicecalendar.data.DayEntryEditorData
import app.servicecalendar.data.DayEntryEditorRow
import app.servicecalendar.domain.AmountOutOfRange
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.completedCount
import app.servicecalendar.domain.percentDone
import app.servicecalendar.domain.dayTotal as sumDayTotal
import app.servicecalendar.domain.lineTotal as computeLineTotal
import app.servicecalendar.domain.sumAmounts
import app.servicecalendar.model.Place
import app.servicecalendar.money.MinorAmount
import kotlinx.datetime.LocalDate

class DayEntryEditorState(data: DayEntryEditorData) {
    val date: LocalDate = data.date
    val place: Place = data.place

    val generation: Long = data.generation

    val rows: List<DayEntryEditorRow> = data.rows
    private val otherPlacesTotal: MinorAmount = data.otherPlacesTotal

    private val texts = mutableStateMapOf<Long, String>().apply {
        for (row in data.rows) put(row.procedureId, row.savedQuantity?.toString().orEmpty())
    }

    private val savedScheduled: Int? = data.scheduled

    var scheduledText: String by mutableStateOf(data.scheduled?.toString().orEmpty())
        private set

    private val repositoryErrors = mutableStateMapOf<Long, String>()

    var generalError: String? by mutableStateOf(null)
        private set

    fun text(procedureId: Long): String = texts[procedureId].orEmpty()

    fun updateQuantity(procedureId: Long, text: String) {
        if (rowById(procedureId) == null) return
        texts[procedureId] = text
        repositoryErrors.remove(procedureId)
        generalError = null
    }

    fun updateScheduled(text: String) {
        scheduledText = text
        scheduledRepositoryError = null
        generalError = null
    }

    private var scheduledRepositoryError: String? by mutableStateOf(null)

    private val scheduledParsed: CountInput
        get() = parseCountInput(scheduledText)

    /** A as typed: null for blank or invalid input. */
    val scheduled: Int?
        get() = (scheduledParsed as? CountInput.Ok)?.value

    val scheduledError: String?
        get() = scheduledRepositoryError ?: (scheduledParsed as? CountInput.Invalid)?.message

    /** R: the sum of the quantities typed so far; invalid rows count as 0. */
    val completed: Long
        get() = completedCount(rows.map { quantity(it.procedureId) ?: 0 })

    /** % for the current A and R; null while A is blank or 0. */
    val percent: Double?
        get() = percentDone(completed, scheduled)

    fun quantity(procedureId: Long): Int? = (parse(text(procedureId)) as? Parsed.Ok)?.value

    fun rowError(procedureId: Long): String? = repositoryErrors[procedureId] ?: evaluate(procedureId).error

    fun lineTotal(procedureId: Long): MinorAmount? = evaluate(procedureId).total

    val placeTotal: MinorAmount?
        get() = try {
            sumAmounts(rows.mapNotNull { evaluate(it.procedureId).total })
        } catch (e: AmountOutOfRange) {
            null
        }

    val dayTotal: MinorAmount?
        get() {
            val place = placeTotal ?: return null
            return try {
                sumDayTotal(listOf(otherPlacesTotal, place))
            } catch (e: AmountOutOfRange) {
                null
            }
        }

    val totalsError: String?
        get() = if (dayTotal == null) EntryStrings.ERROR_TOTAL_TOO_LARGE else null

    val isValid: Boolean
        get() = repositoryErrors.isEmpty() && scheduledError == null && rows.all { evaluate(it.procedureId).error == null } && dayTotal != null

    val isDirty: Boolean
        get() = scheduledParsed is CountInput.Invalid || scheduled != savedScheduled || rows.any { row ->
            val parsed = parse(text(row.procedureId))
            parsed !is Parsed.Ok || parsed.value != (row.savedQuantity ?: 0)
        }

    fun toQuantities(): Map<Long, Int>? {
        if (!isValid) return null
        return rows.associate { it.procedureId to checkNotNull(quantity(it.procedureId)) }
    }

    fun applyError(error: ValidationError) {
        if (error.field == "scheduled") {
            scheduledRepositoryError = EntryStrings.ERROR_SCHEDULED_REJECTED
            return
        }
        val procedureId = error.field.removePrefix(QUANTITY_PREFIX).toLongOrNull()
        if (error.field.startsWith(QUANTITY_PREFIX) && procedureId != null && rowById(procedureId) != null) {
            repositoryErrors[procedureId] = EntryStrings.ERROR_ROW_REJECTED
        } else {
            generalError = error.reason.replaceFirstChar { it.uppercase() } + "."
        }
    }

    fun showGeneralError(message: String) {
        generalError = message
    }

    private fun rowById(procedureId: Long): DayEntryEditorRow? = rows.firstOrNull { it.procedureId == procedureId }

    private class Evaluation(val total: MinorAmount?, val error: String?)

    private fun evaluate(procedureId: Long): Evaluation {
        val row = rowById(procedureId) ?: return Evaluation(null, null)
        return when (val parsed = parse(text(procedureId))) {
            is Parsed.Ok -> try {
                Evaluation(computeLineTotal(row.price, parsed.value), null)
            } catch (e: AmountOutOfRange) {
                Evaluation(null, EntryStrings.ERROR_LINE_TOO_LARGE)
            }
            is Parsed.Invalid -> Evaluation(null, parsed.message)
        }
    }

    private sealed interface Parsed {
        class Ok(val value: Int) : Parsed
        class Invalid(val message: String) : Parsed
    }

    private fun parse(text: String): Parsed = when (val parsed = parseCountInput(text)) {
        CountInput.Empty -> Parsed.Ok(0)
        is CountInput.Ok -> Parsed.Ok(parsed.value)
        is CountInput.Invalid -> Parsed.Invalid(parsed.message)
    }

    private companion object {
        const val QUANTITY_PREFIX = "quantity."
    }
}
