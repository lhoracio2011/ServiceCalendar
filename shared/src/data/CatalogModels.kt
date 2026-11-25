package app.servicecalendar.data

import app.servicecalendar.model.Place
import app.servicecalendar.model.PlaceRate
import app.servicecalendar.model.Procedure

data class PlaceWithRates(
    val place: Place,
    val rates: List<PlaceRate>,
)

data class PlaceDraft(
    val id: Long?,
    val name: String,
    val address: String?,
    val color: Long,
    val rates: Map<Long, Long>,
)

enum class RemovalResult { Deleted, Archived }

data class CatalogSnapshot(
    val generation: Long,
    val procedures: List<Procedure>,
    val places: List<PlaceWithRates>,
)
