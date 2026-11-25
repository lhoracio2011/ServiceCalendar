package app.servicecalendar.ui

import app.servicecalendar.domain.AmountOutOfRange
import app.servicecalendar.domain.DomainException
import app.servicecalendar.domain.LimitExceeded
import app.servicecalendar.domain.StaleGeneration
import app.servicecalendar.domain.StorageBusy
import app.servicecalendar.domain.ValidationError

/** Messages shown to the user for domain errors. */
internal object ErrorStrings {
    fun message(error: DomainException): String = when (error) {
        is ValidationError -> "Check the \"${error.field}\" field."
        is LimitExceeded -> "Limit reached: no more than ${error.max} ${error.what}."
        is AmountOutOfRange -> "The amount is too large. Reduce the price or quantity."
        is StaleGeneration -> "The data was changed elsewhere. Close the editor and try again."
        is StorageBusy -> "Storage is busy. Please try again in a moment."
    }
}
