package app.servicecalendar.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import app.servicecalendar.ui.rememberEditorViewModelScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.servicecalendar.AppContainer
import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.DataStore
import app.servicecalendar.ui.theme.PlaceColorPalette
import kotlin.random.Random

class PlaceEditorViewModel(
    placeId: Long?,
    repository: CatalogRepository = AppContainer.instance.catalogRepository,
    random: Random = Random,
    store: DataStore = AppContainer.instance.dataStore,
) : ViewModel() {
    private val model = PlaceEditorModel(
        placeId = placeId,
        scope = viewModelScope,
        snapshots = repository.snapshots,
        savePlace = { draft, generation -> repository.savePlace(draft, generation) },
        removePlace = { id, generation -> repository.removePlace(id, generation) },
        generation = store.dataGeneration,
        restoring = store.isRestoring,
        random = random,
    )

    val state: PlaceEditorState? get() = model.state

    val loadError: String? get() = model.loadError

    val saving: Boolean get() = model.saving

    val closed: Boolean get() = model.closed

    val staleData: Boolean get() = model.staleData

    val blocked: Boolean get() = model.blocked

    val restoredNotice: Boolean get() = model.restoredNotice

    suspend fun keepCatalogFresh() = model.keepCatalogFresh()

    fun save() = model.save()

    fun remove() = model.remove()

    fun reload() = model.reload()

    fun dismissRestoredNotice() = model.dismissRestoredNotice()
}

@Composable
fun PlaceEditorScreen(placeId: Long?, onClose: () -> Unit) {
    val editorScope = rememberEditorViewModelScope("place-editor-host")
    val viewModel = viewModel(viewModelStoreOwner = editorScope.owner, key = "place-editor-${placeId ?: "new"}") {
        PlaceEditorViewModel(placeId)
    }
    val onClose = {
        editorScope.finish()
        onClose()
    }
    LaunchedEffect(viewModel) { viewModel.keepCatalogFresh() }
    LaunchedEffect(viewModel.closed) { if (viewModel.closed) onClose() }

    if (viewModel.restoredNotice) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoredNotice,
            title = { Text(CatalogStrings.RESTORED_TITLE) },
            text = { Text(CatalogStrings.PLACE_RESTORED_TEXT) },
            confirmButton = { TextButton(onClick = viewModel::dismissRestoredNotice) { Text(CatalogStrings.RESTORED_OK) } },
        )
    }

    val state = viewModel.state
    if (state == null) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            CatalogHeader(
                title = if (placeId == null) CatalogStrings.PLACE_NEW_TITLE else CatalogStrings.PLACE_EDIT_TITLE,
                navigation = { BackButton(onClose) },
            )
            CenteredText(viewModel.loadError ?: CatalogStrings.LOADING)
        }
    } else {
        PlaceEditorContent(
            state = state,
            saving = viewModel.saving,
            onSave = viewModel::save,
            onClose = onClose,
            onRemove = if (state.isNew) null else viewModel::remove,
            blocked = viewModel.blocked,
            staleData = viewModel.staleData,
            onReload = viewModel::reload,
        )
    }
}

@Composable
fun PlaceEditorContent(
    state: PlaceEditorState,
    saving: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onRemove: (() -> Unit)?,
    blocked: Boolean = false,
    staleData: Boolean = false,
    onReload: () -> Unit = {},
) {
    val editable = !saving && !blocked && !staleData
    var confirmRemoval by remember { mutableStateOf(false) }
    var paletteOpen by rememberSaveable { mutableStateOf(state.isNew) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        CatalogHeader(
            title = if (state.isNew) CatalogStrings.PLACE_NEW_TITLE else CatalogStrings.PLACE_EDIT_TITLE,
            navigation = { BackButton(onClose) },
            actions = {
                TextButton(
                    onClick = onSave,
                    enabled = editable,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text(CatalogStrings.SAVE) }
            },
        )
        if (blocked) {
            Text(
                CatalogStrings.RESTORING,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = state::updateName,
                enabled = !blocked,
                label = { Text(CatalogStrings.PLACE_NAME) },
                singleLine = true,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.address,
                onValueChange = state::updateAddress,
                enabled = !blocked,
                label = { Text(CatalogStrings.PLACE_ADDRESS) },
                isError = state.addressError != null,
                supportingText = state.addressError?.let { { Text(it) } },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            ColorSection(
                color = state.color,
                error = state.colorError,
                open = paletteOpen,
                onToggle = { paletteOpen = !paletteOpen },
                onPick = { if (!blocked) state.updateColor(it) },
            )

            Text(CatalogStrings.PLACE_PROCEDURES, style = MaterialTheme.typography.titleMedium)
            if (state.catalog.isEmpty()) {
                Text(
                    CatalogStrings.PLACE_NO_PROCEDURES,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.catalog.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline)) {
                    state.catalog.forEachIndexed { index, procedure ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        ProcedureRateRow(
                            name = procedure.name,
                            selected = state.isSelected(procedure.id),
                            price = state.priceText(procedure.id),
                            error = state.rateError(procedure.id),
                            enabled = !blocked,
                            onSelectedChange = { state.toggleProcedure(procedure.id, it) },
                            onPriceChange = { state.updatePrice(procedure.id, it) },
                        )
                    }
                }
            }

            state.generalError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (onRemove != null) {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                OutlinedButton(
                    onClick = { confirmRemoval = true },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(CatalogStrings.PLACE_REMOVE, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (staleData) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(CatalogStrings.STALE_TITLE) },
            text = { Text(CatalogStrings.STALE_TEXT) },
            confirmButton = { TextButton(onClick = onReload) { Text(CatalogStrings.STALE_RELOAD) } },
            dismissButton = { TextButton(onClick = onClose) { Text(CatalogStrings.CLOSE) } },
        )
    }

    if (confirmRemoval && onRemove != null && editable) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text(CatalogStrings.PLACE_REMOVE_TITLE) },
            text = { Text(CatalogStrings.placeRemoveText(state.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    onRemove()
                }) { Text(CatalogStrings.DELETE, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text(CatalogStrings.CANCEL) } },
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = CatalogStrings.CANCEL }) {
        Text(CatalogStrings.BACK, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ColorSection(
    color: Long,
    error: String?,
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(CatalogStrings.PLACE_COLOR, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (open) CatalogStrings.COLOR_COLLAPSE_ICON else CatalogStrings.COLOR_EXPAND_ICON, modifier = Modifier.padding(end = 12.dp))
            ColorSwatch(color, size = 36.dp)
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (open) {
            val shape = RoundedCornerShape(8.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlaceColorPalette.chunked(PALETTE_COLUMNS).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowColors.forEach { swatch ->
                            ColorSwatch(
                                color = swatch,
                                size = 40.dp,
                                selected = swatch == color,
                                modifier = Modifier
                                    .clickable { onPick(swatch) }
                                    .semantics { contentDescription = CatalogStrings.PLACE_CHOOSE_COLOR },
                            )
                        }
                    }
                }
                Text(
                    CatalogStrings.PLACE_CHOOSE_COLOR,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val PALETTE_COLUMNS = 5

@Composable
private fun ProcedureRateRow(
    name: String,
    selected: Boolean,
    price: String,
    error: String?,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onPriceChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val priceEnabled = selected && enabled
    Column {
        Row(Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = enabled)
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = enabled) { onSelectedChange(!selected) }
                        .padding(end = 8.dp),
                )
            }
            VerticalDivider(color = colors.outline)
            Box(
                modifier = Modifier.width(112.dp).fillMaxHeight().padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                BasicTextField(
                    value = price,
                    onValueChange = onPriceChange,
                    enabled = priceEnabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = if (priceEnabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
                        textAlign = TextAlign.End,
                    ),
                    cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (price.isEmpty()) {
                                Text(CatalogStrings.pricePlaceholder(), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface.copy(alpha = 0.38f))
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${CatalogStrings.PLACE_PRICE}: $name" },
                )
            }
        }
        if (error != null) {
            Text(
                error,
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
        }
    }
}
