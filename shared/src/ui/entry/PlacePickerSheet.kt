package app.servicecalendar.ui.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.servicecalendar.AppContainer
import app.servicecalendar.data.CatalogRepository
import app.servicecalendar.data.PlaceWithRates
import app.servicecalendar.ui.catalog.ColorSwatch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

class PlacePickerViewModel(
    repository: CatalogRepository = AppContainer.instance.catalogRepository,
) : ViewModel() {
    val places: StateFlow<List<PlaceWithRates>?> = repository.places
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerSheet(date: LocalDate, onPick: (placeId: Long) -> Unit, onDismiss: () -> Unit) {
    val viewModel = viewModel { PlacePickerViewModel() }
    val places by viewModel.places.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        PlacePickerContent(date = date, places = places, onPick = onPick)
    }
}

@Composable
fun PlacePickerContent(date: LocalDate, places: List<PlaceWithRates>?, onPick: (placeId: Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                EntryStrings.PICKER_TITLE,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                EntryStrings.pickerSubtitle(date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        when {
            places == null -> PickerMessage(EntryStrings.LOADING)
            places.isEmpty() -> PickerMessage(EntryStrings.PICKER_EMPTY)
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(places, key = { it.place.id }) { item ->
                    PickerRow(item, onClick = { onPick(item.place.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun PickerMessage(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
private fun PickerRow(item: PlaceWithRates, onClick: () -> Unit) {
    val place = item.place
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = EntryStrings.pickPlaceAction(place.name), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = EntryStrings.pickPlaceDescription(place.name, place.address)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorSwatch(place.color, size = 32.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            place.address?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
