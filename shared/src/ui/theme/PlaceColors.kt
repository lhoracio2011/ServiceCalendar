package app.servicecalendar.ui.theme

import androidx.compose.ui.graphics.Color

val PlaceColorPalette: List<Long> = listOf(
    0xFF880015L,
    0xFFED1C24L,
    0xFFFFF200L,
    0xFF3F48CCL,
    0xFF22B14CL,
    0xFF000000L,
    0xFF7F7F7FL,
    0xFFFF7F27L,
    0xFFFFC90EL,
    0xFFB97A57L,
)

fun Long.toComposeColor(): Color = Color(this.toInt())

fun pickPlaceColor(used: Collection<Long>, random: kotlin.random.Random = kotlin.random.Random): Long {
    val free = PlaceColorPalette.filter { it !in used }
    return (free.ifEmpty { PlaceColorPalette }).random(random)
}
