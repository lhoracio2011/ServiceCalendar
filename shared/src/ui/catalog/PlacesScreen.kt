package app.servicecalendar.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

class PlacesViewModel(
    repository: CatalogRepository = AppContainer.instance.catalogRepository,
) : ViewModel() {
    val places: StateFlow<List<PlaceWithRates>?> = repository.places
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun PlacesScreen(onOpenPlace: (Long?) -> Unit) {
    val viewModel = viewModel { PlacesViewModel() }
    val places by viewModel.places.collectAsStateWithLifecycle()
    PlacesContent(places = places, onOpenPlace = onOpenPlace)
}

@Composable
fun PlacesContent(places: List<PlaceWithRates>?, onOpenPlace: (Long?) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                places == null -> CenteredText(CatalogStrings.LOADING)
                places.isEmpty() -> CenteredText(CatalogStrings.PLACES_EMPTY)
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(places, key = { it.place.id }) { item ->
                        PlaceRow(item, onClick = { onOpenPlace(item.place.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
        FloatingActionButton(
            onClick = { onOpenPlace(null) },
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .semantics { contentDescription = CatalogStrings.ADD_PLACE },
        ) {
            Text(CatalogStrings.ADD, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun PlaceRow(item: PlaceWithRates, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorSwatch(item.place.color, size = 32.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.place.address?.let {
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

@Composable
internal fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}
