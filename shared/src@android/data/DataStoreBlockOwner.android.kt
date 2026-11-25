package app.servicecalendar.data

private val blockOwner = ThreadLocal<Any?>()

internal actual var currentDataStoreBlockOwner: Any?
    get() = blockOwner.get()
    set(value) = if (value == null) blockOwner.remove() else blockOwner.set(value)
