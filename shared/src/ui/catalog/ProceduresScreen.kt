package app.servicecalendar.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.servicecalendar.AppContainer
import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.CatalogSnapshot
import app.servicecalendar.data.DataStore
import app.servicecalendar.domain.CatalogLimits
import app.servicecalendar.domain.nameTooLongReason
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.ValidationError
import app.servicecalendar.model.Procedure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProcedureNameDialog(
    val procedureId: Long?,
    val name: String,
    val generation: Long,
    val error: String? = null,
    val saving: Boolean = false,
)

data class ProcedureListing(val generation: Long?, val procedures: List<Procedure>)

internal class ProceduresModel(
    private val scope: CoroutineScope,
    snapshots: Flow<CatalogSnapshot>,
    createProcedure: suspend (name: String, openedGeneration: Long) -> Unit,
    renameProcedure: suspend (id: Long, name: String, openedGeneration: Long) -> Unit,
    removeProcedure: suspend (id: Long, openedGeneration: Long) -> Unit,
    private val generation: StateFlow<Long?>,
    private val restoring: StateFlow<Boolean>,
) {
    private val create = createProcedure
    private val rename = renameProcedure
    private val erase = removeProcedure

    val listing: StateFlow<ProcedureListing?> = snapshots
        .map { ProcedureListing(it.generation, it.procedures) }
        .catch { emit(ProcedureListing(null, emptyList())) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    var dialog: ProcedureNameDialog? by mutableStateOf(null)
        private set

    var addName: String by mutableStateOf("")
        private set
    var addError: String? by mutableStateOf(null)
        private set
    var addSaving: Boolean by mutableStateOf(false)
        private set

    var pendingRemoval: Procedure? by mutableStateOf(null)
        private set
    private var pendingRemovalGeneration: Long? = null

    var message: String? by mutableStateOf(null)
        private set

    var staleData: Boolean by mutableStateOf(false)
        private set

    var blocked: Boolean by mutableStateOf(restoring.value)
        private set

    var restoredNotice: Boolean by mutableStateOf(false)
        private set

    init {
        scope.launch { restoring.collect { blocked = it } }
        scope.launch { generation.collect { onGeneration(it) } }
    }

    private fun actionsAllowed(): Boolean = !staleData && !blocked && !restoring.value

    fun updateAddName(name: String) {
        addName = name.take(CatalogLimits.MAX_NAME_LENGTH)
        addError = null
    }

    fun submitAdd(listedGeneration: Long?) {
        if (addSaving || !actionsAllowed()) return
        val opened = listedGeneration ?: generation.value ?: return
        val name = addName
        if (name.isBlank()) {
            addError = CatalogStrings.ERROR_NAME_REQUIRED
            return
        }
        addSaving = true
        addError = null
        scope.launch {
            var error: String? = null
            val stale = try {
                create(name, opened)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleGeneration) {
                true
            } catch (e: Exception) {
                error = when {
                    e is ValidationError && e.field == "name" ->
                        if (e.reason == nameTooLongReason()) CatalogStrings.nameTooLong() else CatalogStrings.ERROR_NAME_REQUIRED
                    else -> e.toCatalogMessage()
                }
                false
            }
            addSaving = false
            when {
                stale -> staleData = true
                error == null -> if (addName == name) addName = ""
                else -> addError = error
            }
        }
    }

    fun startRename(procedure: Procedure, listedGeneration: Long?) {
        if (!actionsAllowed()) return
        val opened = listedGeneration ?: generation.value ?: return
        dialog = ProcedureNameDialog(procedureId = procedure.id, name = procedure.name, generation = opened)
    }

    fun updateDialogName(name: String) {
        dialog = dialog?.copy(name = name.take(CatalogLimits.MAX_NAME_LENGTH), error = null)
    }

    fun dismissDialog() {
        dialog = null
    }

    fun submitDialog() {
        val current = dialog ?: return
        if (current.saving || !actionsAllowed()) return
        if (current.name.isBlank()) {
            dialog = current.copy(error = CatalogStrings.ERROR_NAME_REQUIRED)
            return
        }
        val submitting = current.copy(saving = true, error = null)
        dialog = submitting
        scope.launch {
            var error: String? = null
            val stale = try {
                rename(checkNotNull(submitting.procedureId), submitting.name, submitting.generation)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleGeneration) {
                true
            } catch (e: Exception) {
                error = when {
                    e is ValidationError && e.field == "name" ->
                        if (e.reason == nameTooLongReason()) CatalogStrings.nameTooLong() else CatalogStrings.ERROR_NAME_REQUIRED
                    else -> e.toCatalogMessage()
                }
                false
            }
            val stillOpen = dialog === submitting
            when {
                stale -> {
                    if (stillOpen) dialog = null
                    staleData = true
                }
                error == null -> if (stillOpen) dialog = null
                !stillOpen -> Unit
                generation.value.let { it != null && it != submitting.generation } -> {
                    dialog = null
                    restoredNotice = true
                }
                else -> dialog = submitting.copy(saving = false, error = error)
            }
        }
    }

    fun askRemove(procedure: Procedure, listedGeneration: Long?) {
        if (!actionsAllowed()) return
        pendingRemovalGeneration = listedGeneration ?: generation.value ?: return
        pendingRemoval = procedure
    }

    fun dismissRemoval() {
        pendingRemoval = null
        pendingRemovalGeneration = null
    }

    fun confirmRemoval() {
        val procedure = pendingRemoval ?: return
        val opened = pendingRemovalGeneration ?: return
        if (!actionsAllowed()) return
        dismissRemoval()
        scope.launch {
            try {
                erase(procedure.id, opened)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StaleGeneration) {
                staleData = true
            } catch (e: Exception) {
                message = e.toCatalogMessage()
            }
        }
    }

    fun dismissMessage() {
        message = null
    }

    fun reload() {
        staleData = false
        dialog = null
        addError = null
        dismissRemoval()
    }

    fun dismissRestoredNotice() {
        restoredNotice = false
    }

    private fun onGeneration(value: Long?) {
        if (value == null) return
        var discarded = false
        dialog?.let {
            if (!it.saving && it.generation != value) {
                dialog = null
                discarded = true
            }
        }
        if (pendingRemoval != null && pendingRemovalGeneration != value) {
            dismissRemoval()
            discarded = true
        }
        if (discarded) restoredNotice = true
    }
}

class ProceduresViewModel(
    repository: CatalogRepository = AppContainer.instance.catalogRepository,
    store: DataStore = AppContainer.instance.dataStore,
) : ViewModel() {
    private val model = ProceduresModel(
        scope = viewModelScope,
        snapshots = repository.snapshots,
        createProcedure = { name, generation -> repository.createProcedure(name, generation) },
        renameProcedure = { id, name, generation -> repository.renameProcedure(id, name, generation) },
        removeProcedure = { id, generation -> repository.removeProcedure(id, generation) },
        generation = store.dataGeneration,
        restoring = store.isRestoring,
    )

    val listing: StateFlow<ProcedureListing?> get() = model.listing

    val dialog: ProcedureNameDialog? get() = model.dialog
    val pendingRemoval: Procedure? get() = model.pendingRemoval
    val message: String? get() = model.message
    val staleData: Boolean get() = model.staleData
    val blocked: Boolean get() = model.blocked
    val restoredNotice: Boolean get() = model.restoredNotice

    val addName: String get() = model.addName
    val addError: String? get() = model.addError
    val addSaving: Boolean get() = model.addSaving

    fun updateAddName(name: String) = model.updateAddName(name)
    fun submitAdd(listedGeneration: Long?) = model.submitAdd(listedGeneration)
    fun startRename(procedure: Procedure, listedGeneration: Long?) = model.startRename(procedure, listedGeneration)
    fun updateDialogName(name: String) = model.updateDialogName(name)
    fun dismissDialog() = model.dismissDialog()
    fun submitDialog() = model.submitDialog()
    fun askRemove(procedure: Procedure, listedGeneration: Long?) = model.askRemove(procedure, listedGeneration)
    fun dismissRemoval() = model.dismissRemoval()
    fun confirmRemoval() = model.confirmRemoval()
    fun dismissMessage() = model.dismissMessage()
    fun reload() = model.reload()
    fun dismissRestoredNotice() = model.dismissRestoredNotice()
}

@Composable
fun ProceduresScreen() {
    val viewModel = viewModel { ProceduresViewModel() }
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val shown = listing
    ProceduresContent(
        procedures = shown?.procedures,
        dialog = viewModel.dialog,
        pendingRemoval = viewModel.pendingRemoval,
        message = viewModel.message,
        addName = viewModel.addName,
        addError = viewModel.addError,
        addSaving = viewModel.addSaving,
        onAddNameChange = viewModel::updateAddName,
        onAdd = { viewModel.submitAdd(shown?.generation) },
        onRename = { viewModel.startRename(it, shown?.generation) },
        onRemove = { viewModel.askRemove(it, shown?.generation) },
        onDialogNameChange = viewModel::updateDialogName,
        onDialogSubmit = viewModel::submitDialog,
        onDialogDismiss = viewModel::dismissDialog,
        onRemovalConfirm = viewModel::confirmRemoval,
        onRemovalDismiss = viewModel::dismissRemoval,
        onMessageDismiss = viewModel::dismissMessage,
        blocked = viewModel.blocked,
        staleData = viewModel.staleData,
        onReload = viewModel::reload,
        restoredNotice = viewModel.restoredNotice,
        onRestoredNoticeDismiss = viewModel::dismissRestoredNotice,
    )
}

@Composable
fun ProceduresContent(
    procedures: List<Procedure>?,
    dialog: ProcedureNameDialog?,
    pendingRemoval: Procedure?,
    message: String?,
    addName: String,
    addError: String?,
    addSaving: Boolean,
    onAddNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (Procedure) -> Unit,
    onRemove: (Procedure) -> Unit,
    onDialogNameChange: (String) -> Unit,
    onDialogSubmit: () -> Unit,
    onDialogDismiss: () -> Unit,
    onRemovalConfirm: () -> Unit,
    onRemovalDismiss: () -> Unit,
    onMessageDismiss: () -> Unit,
    blocked: Boolean = false,
    staleData: Boolean = false,
    onReload: () -> Unit = {},
    restoredNotice: Boolean = false,
    onRestoredNoticeDismiss: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        if (blocked) {
            Text(
                CatalogStrings.RESTORING,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        OutlinedTextField(
            value = addName,
            onValueChange = onAddNameChange,
            label = { Text(CatalogStrings.PROCEDURE_NAME) },
            singleLine = true,
            enabled = !blocked,
            isError = addError != null,
            supportingText = addError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!blocked) onAdd() }),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        val lineColor = MaterialTheme.colorScheme.outline
        when {
            procedures == null -> Box(Modifier.fillMaxWidth().weight(1f)) { CenteredText(CatalogStrings.LOADING) }
            else -> LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(procedures, key = { it.id }) { procedure ->
                    ProcedureRow(
                        procedure,
                        enabled = !blocked,
                        onRename = { onRename(procedure) },
                        onRemove = { onRemove(procedure) },
                        modifier = Modifier.border(0.5.dp, lineColor),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onAdd,
            enabled = !blocked && !addSaving,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(CatalogStrings.ADD_PROCEDURE, style = MaterialTheme.typography.titleMedium)
        }
    }

    if (dialog != null) {
        ProcedureNameDialogView(dialog, blocked, onDialogNameChange, onDialogSubmit, onDialogDismiss)
    }
    if (pendingRemoval != null && !blocked) {
        AlertDialog(
            onDismissRequest = onRemovalDismiss,
            title = { Text(CatalogStrings.PROCEDURE_REMOVE_TITLE) },
            text = { Text(CatalogStrings.procedureRemoveText(pendingRemoval.name)) },
            confirmButton = {
                TextButton(onClick = onRemovalConfirm) {
                    Text(CatalogStrings.DELETE, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onRemovalDismiss) { Text(CatalogStrings.CANCEL) } },
        )
    }
    if (staleData) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(CatalogStrings.STALE_TITLE) },
            text = { Text(CatalogStrings.STALE_TEXT) },
            confirmButton = { TextButton(onClick = onReload) { Text(CatalogStrings.STALE_RELOAD) } },
        )
    }
    if (restoredNotice) {
        AlertDialog(
            onDismissRequest = onRestoredNoticeDismiss,
            title = { Text(CatalogStrings.RESTORED_TITLE) },
            text = { Text(CatalogStrings.PROCEDURES_RESTORED_TEXT) },
            confirmButton = { TextButton(onClick = onRestoredNoticeDismiss) { Text(CatalogStrings.RESTORED_OK) } },
        )
    }
    if (message != null) {
        AlertDialog(
            onDismissRequest = onMessageDismiss,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onMessageDismiss) { Text(CatalogStrings.DIALOG_OK) } },
        )
    }
}

@Composable
private fun ProcedureRow(
    procedure: Procedure,
    enabled: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onRename).padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            procedure.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onRename,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = CatalogStrings.PROCEDURE_RENAME },
        ) { Text(CatalogStrings.PROCEDURE_EDIT_ICON) }
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = CatalogStrings.DELETE },
        ) { Text(CatalogStrings.PROCEDURE_DELETE_ICON, color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ProcedureNameDialogView(
    dialog: ProcedureNameDialog,
    blocked: Boolean,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(CatalogStrings.PROCEDURE_RENAME_TITLE)
        },
        text = {
            OutlinedTextField(
                value = dialog.name,
                onValueChange = onNameChange,
                label = { Text(CatalogStrings.PROCEDURE_NAME) },
                singleLine = true,
                isError = dialog.error != null,
                supportingText = dialog.error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!blocked) onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !dialog.saving && !blocked) { Text(CatalogStrings.SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(CatalogStrings.CANCEL) } },
    )
}
