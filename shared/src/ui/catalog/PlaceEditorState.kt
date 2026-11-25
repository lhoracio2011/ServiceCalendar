package app.servicecalendar.ui.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.servicecalendar.data.CatalogSnapshot
import app.servicecalendar.data.PlaceDraft
import app.servicecalendar.data.PlaceWithRates
import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.domain.addressTooLongReason
import app.servicecalendar.domain.nameTooLongReason
import app.servicecalendar.domain.validatePlaceDraft
import app.servicecalendar.model.Procedure
import app.servicecalendar.money.formatAmount
import app.servicecalendar.money.localeDecimalSeparator
import app.servicecalendar.money.parseAmount
import app.servicecalendar.ui.theme.pickPlaceColor
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlaceEditorState private constructor(
    val placeId: Long?,
    name: String,
    address: String,
    color: Long,
    selected: Set<Long>,
    prices: Map<Long, String>,
) {
    var name: String by mutableStateOf(name)
        private set
    var address: String by mutableStateOf(address)
        private set
    var color: Long by mutableStateOf(color)
        private set

    var catalog: List<Procedure> by mutableStateOf(emptyList())
        private set

    var nameError: String? by mutableStateOf(null)
        private set
    var addressError: String? by mutableStateOf(null)
        private set
    var colorError: String? by mutableStateOf(null)
        private set

    var generalError: String? by mutableStateOf(null)
        private set

    private var selectedIds: Set<Long> by mutableStateOf(selected)
    private val priceTexts = mutableStateMapOf<Long, String>().apply { putAll(prices) }
    private val rateErrors = mutableStateMapOf<Long, String>()

    val isNew: Boolean get() = placeId == null

    fun isSelected(procedureId: Long): Boolean = procedureId in selectedIds

    fun priceText(procedureId: Long): String = priceTexts[procedureId].orEmpty()

    fun rateError(procedureId: Long): String? = rateErrors[procedureId]

    fun updateCatalog(procedures: List<Procedure>) {
        catalog = procedures
    }

    fun updateName(value: String) {
        name = value.take(CatalogLimits.MAX_NAME_LENGTH)
        nameError = null
    }

    fun updateAddress(value: String) {
        address = value.take(CatalogLimits.MAX_ADDRESS_LENGTH)
        addressError = null
    }

    fun updateColor(value: Long) {
        color = value
        colorError = null
    }

    fun toggleProcedure(procedureId: Long, selected: Boolean) {
        selectedIds = if (selected) selectedIds + procedureId else selectedIds - procedureId
        rateErrors.remove(procedureId)
    }

    fun updatePrice(procedureId: Long, text: String) {
        priceTexts[procedureId] = text
        rateErrors.remove(procedureId)
    }

    fun toDraft(): PlaceDraft? {
        clearErrors()
        if (name.isBlank()) nameError = CatalogStrings.ERROR_NAME_REQUIRED

        val rates = LinkedHashMap<Long, Long>()
        for (procedure in catalog) {
            if (procedure.id !in selectedIds) continue
            val text = priceText(procedure.id)
            val amount = if (text.isBlank()) null else parseAmount(text)
            if (amount == null) {
                rateErrors[procedure.id] =
                    if (text.isBlank()) CatalogStrings.ERROR_PRICE_REQUIRED else CatalogStrings.priceInvalid()
            } else {
                rates[procedure.id] = amount.minor
            }
        }
        if (nameError != null || rateErrors.isNotEmpty()) return null

        val draft = PlaceDraft(
            id = placeId,
            name = name.trim(),
            address = address.trim().takeIf { it.isNotEmpty() },
            color = color,
            rates = rates,
        )
        return try {
            validatePlaceDraft(draft)
            draft
        } catch (e: ValidationError) {
            applyError(e)
            null
        }
    }

    fun applyError(error: ValidationError) {
        val field = error.field
        val rateId = if (field.startsWith(RATE_PREFIX)) field.removePrefix(RATE_PREFIX).toLongOrNull() else null
        when {
            field == "name" ->
                nameError = if (error.reason == nameTooLongReason()) CatalogStrings.nameTooLong() else CatalogStrings.ERROR_NAME_REQUIRED
            field == "address" && error.reason == addressTooLongReason() -> addressError = CatalogStrings.addressTooLong()
            field == "color" -> colorError = CatalogStrings.ERROR_COLOR_INVALID
            rateId != null -> rateErrors[rateId] = CatalogStrings.priceInvalid()
            else -> generalError = error.reason.replaceFirstChar { it.uppercase() } + "."
        }
    }

    fun showGeneralError(message: String) {
        generalError = message
    }

    private fun clearErrors() {
        nameError = null
        addressError = null
        colorError = null
        generalError = null
        rateErrors.clear()
    }

    companion object {
        private const val RATE_PREFIX = "rate."

        fun forNewPlace(
            usedColors: Collection<Long>,
            random: Random = Random,
        ): PlaceEditorState = PlaceEditorState(
            placeId = null,
            name = "",
            address = "",
            color = pickPlaceColor(usedColors, random),
            selected = emptySet(),
            prices = emptyMap(),
        )

        fun forExisting(
            place: PlaceWithRates,
            decimalSeparator: Char = localeDecimalSeparator(),
        ): PlaceEditorState = PlaceEditorState(
            placeId = place.place.id,
            name = place.place.name,
            address = place.place.address.orEmpty(),
            color = place.place.color,
            selected = place.rates.map { it.procedureId }.toSet(),
            prices = place.rates.associate { it.procedureId to formatAmount(it.amountMinor, decimalSeparator) },
        )
    }
}

internal class PlaceEditorModel(
    private val placeId: Long?,
    private val scope: CoroutineScope,
    private val snapshots: Flow<CatalogSnapshot>,
    savePlace: suspend (draft: PlaceDraft, openedGeneration: Long) -> Unit,
    removePlace: suspend (id: Long, openedGeneration: Long) -> Unit,
    private val generation: StateFlow<Long?>,
    private val restoring: StateFlow<Boolean>,
    private val random: Random = Random,
    private val decimalSeparator: Char = localeDecimalSeparator(),
) {
    private val writePlace = savePlace
    private val erasePlace = removePlace

    var state: PlaceEditorState? by mutableStateOf(null)
        private set

    var loadError: String? by mutableStateOf(null)
        private set

    var saving: Boolean by mutableStateOf(false)
        private set

    var closed: Boolean by mutableStateOf(false)
        private set

    var staleData: Boolean by mutableStateOf(false)
        private set

    var blocked: Boolean by mutableStateOf(restoring.value)
        private set

    var restoredNotice: Boolean by mutableStateOf(false)
        private set

    private var openedGeneration: Long? = null
    private var loadJob: Job? = null
    private var reopenAfterAction = false

    init {
        load(afterRestore = false)
        scope.launch { restoring.collect { blocked = it } }
        scope.launch { generation.collect { onGeneration(it) } }
    }

    suspend fun keepCatalogFresh() {
        tryCatalog(onError = {}) {
            snapshots.collect { snapshot ->
                val current = state ?: return@collect
                val opened = openedGeneration ?: return@collect
                if (snapshot.generation == opened) current.updateCatalog(snapshot.procedures) else onGeneration(snapshot.generation)
            }
        }
    }

    fun reload() {
        if (saving) return
        load(afterRestore = false)
    }

    fun dismissRestoredNotice() {
        restoredNotice = false
    }

    fun save() {
        val current = state ?: return
        val opened = openedGeneration ?: return
        if (!actionsAllowed()) return
        val draft = current.toDraft() ?: return
        runAction(opened, onError = { e ->
            if (e is ValidationError) current.applyError(e) else current.showGeneralError(e.toCatalogMessage())
        }) { writePlace(draft, opened) }
    }

    fun remove() {
        val id = placeId ?: return
        val current = state ?: return
        val opened = openedGeneration ?: return
        if (!actionsAllowed()) return
        runAction(opened, onError = { current.showGeneralError(it.toCatalogMessage()) }) { erasePlace(id, opened) }
    }

    private fun actionsAllowed(): Boolean = !saving && !staleData && !closed && !blocked && !restoring.value

    private enum class Outcome { Done, Stale, Failed }

    private fun runAction(opened: Long, onError: (Exception) -> Unit, action: suspend () -> Unit) {
        saving = true
        scope.launch {
            val outcome = try {
                action()
                Outcome.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleGeneration) {
                Outcome.Stale
            } catch (e: Exception) {
                onError(e)
                Outcome.Failed
            } finally {
                saving = false
            }
            val generationChanged = reopenAfterAction || generation.value.let { it != null && it != opened }
            reopenAfterAction = false
            when {
                outcome == Outcome.Done -> closed = true
                outcome == Outcome.Stale -> staleData = true
                generationChanged -> reopenAfterRestore()
            }
        }
    }

    private fun onGeneration(value: Long?) {
        if (value == null || closed) return
        val opened = openedGeneration ?: return
        if (value == opened) return
        when {
            saving -> reopenAfterAction = true
            staleData -> Unit
            else -> reopenAfterRestore()
        }
    }

    private fun reopenAfterRestore() {
        restoredNotice = true
        load(afterRestore = true)
    }

    private fun load(afterRestore: Boolean) {
        loadJob?.cancel()
        state = null
        openedGeneration = null
        loadError = null
        staleData = false
        loadJob = scope.launch {
            tryCatalog(onError = { loadError = it.toCatalogMessage() }) { open(snapshots.first(), afterRestore) }
        }
    }

    private fun open(snapshot: CatalogSnapshot, afterRestore: Boolean) {
        val loaded = if (placeId == null) {
            PlaceEditorState.forNewPlace(snapshot.places.map { it.place.color }, random)
        } else {
            snapshot.places.firstOrNull { it.place.id == placeId }?.let { PlaceEditorState.forExisting(it, decimalSeparator) }
        }
        if (loaded == null) {
            if (afterRestore) restoredNotice = false
            loadError = if (afterRestore) CatalogStrings.PLACE_GONE_AFTER_RESTORE else CatalogStrings.PLACE_NOT_FOUND
            return
        }
        loaded.updateCatalog(snapshot.procedures)
        openedGeneration = snapshot.generation
        state = loaded
        val latest = generation.value
        if (latest != null && latest != snapshot.generation) reopenAfterRestore()
    }
}
