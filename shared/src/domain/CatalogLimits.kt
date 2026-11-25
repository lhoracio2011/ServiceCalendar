package app.servicecalendar.domain

/** Single source of truth for data limits; the snapshot validator uses the same values, so local data can never exceed what a backup accepts. */
object CatalogLimits {
    const val MAX_PROCEDURES: Int = 2_000

    const val MAX_PLACES: Int = 2_000

    const val MAX_PLACE_RATES: Int = 100_000

    const val MAX_WORK_ENTRIES: Int = 200_000

    const val MAX_WORK_LINES: Int = 1_000_000

    const val MAX_NAME_LENGTH: Int = 500

    const val MAX_ADDRESS_LENGTH: Int = 1_000
}
