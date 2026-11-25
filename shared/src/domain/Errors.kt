package app.servicecalendar.domain

sealed class DomainException(message: String) : Exception(message)

class ValidationError(
    val field: String,
    val reason: String,
) : DomainException("$field: $reason")

class AmountOutOfRange : DomainException("Amount is out of the supported range")

class StaleGeneration : DomainException("The local data changed after the editor was opened")

class StorageBusy : DomainException("Storage is busy")

class LimitExceeded(val what: String, val max: Int) : DomainException("Limit reached: no more than $max $what")

fun DomainException.toUserMessage(): String = app.servicecalendar.ui.ErrorStrings.message(this)
