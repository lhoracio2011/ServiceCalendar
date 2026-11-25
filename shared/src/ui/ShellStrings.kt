package app.servicecalendar.ui

internal object ShellStrings {
    const val TAB_CALENDAR = "Calendar"
    const val TAB_SETTINGS = "Settings"
    const val BACK = "Back"

    const val SETTINGS_TITLE = "Settings"

    const val REPORTS_TITLE = "Reports"
    const val REPORTS_SUBTITLE = "Totals per place for a period"
    const val PLACES_TITLE = "Places"
    const val PLACES_SUBTITLE = "Workplaces, colors and prices"
    const val PROCEDURES_TITLE = "Procedures"
    const val PROCEDURES_SUBTITLE = "Your procedure list"
    const val BACKUP_TITLE = "Backup"
    const val BACKUP_SUBTITLE = "Google Drive backup and restore"

    fun title(page: SettingsPage): String = when (page) {
        SettingsPage.Reports -> REPORTS_TITLE
        SettingsPage.Places -> PLACES_TITLE
        SettingsPage.Procedures -> PROCEDURES_TITLE
        SettingsPage.Backup -> BACKUP_TITLE
    }

    fun subtitle(page: SettingsPage): String = when (page) {
        SettingsPage.Reports -> REPORTS_SUBTITLE
        SettingsPage.Places -> PLACES_SUBTITLE
        SettingsPage.Procedures -> PROCEDURES_SUBTITLE
        SettingsPage.Backup -> BACKUP_SUBTITLE
    }
}
