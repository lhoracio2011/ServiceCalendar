package app.servicecalendar.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

private const val STORAGE_DATE_LENGTH = 10

// Entry dates are calendar dates stored as YYYY-MM-DD text, independent of the device time zone.
fun LocalDate.toStorageText(): String {
    if (year !in 0..9999) throw ValidationError("date", "year is outside 0000..9999: $year")
    val y = year.toString().padStart(4, '0')
    val m = month.number.toString().padStart(2, '0')
    val d = day.toString().padStart(2, '0')
    return "$y-$m-$d"
}

fun parseStorageDate(text: String): LocalDate {
    if (text.length != STORAGE_DATE_LENGTH || text[4] != '-' || text[7] != '-') {
        throw ValidationError("date", "expected format YYYY-MM-DD: \"$text\"")
    }
    val digitsOnly = text.indices.all { it == 4 || it == 7 || text[it] in '0'..'9' }
    if (!digitsOnly) throw ValidationError("date", "expected format YYYY-MM-DD: \"$text\"")
    val year = text.substring(0, 4).toInt()
    val month = text.substring(5, 7).toInt()
    val day = text.substring(8, 10).toInt()
    return LocalDate.orNull(year, month, day)
        ?: throw ValidationError("date", "date does not exist: \"$text\"")
}
