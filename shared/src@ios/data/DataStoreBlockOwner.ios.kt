package app.servicecalendar.data

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
internal actual var currentDataStoreBlockOwner: Any? = null
