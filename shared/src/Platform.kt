package app.servicecalendar

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
