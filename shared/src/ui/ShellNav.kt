package app.servicecalendar.ui

internal enum class ShellTab { Calendar, Settings }

internal enum class SettingsPage { Reports, Places, Procedures, Backup }

internal data class ShellNav(
    val tab: ShellTab = ShellTab.Calendar,
    val page: SettingsPage? = null,
) {
    fun selectTab(target: ShellTab): ShellNav = ShellNav(tab = target, page = null)

    fun openPage(target: SettingsPage): ShellNav = ShellNav(tab = ShellTab.Settings, page = target)

    fun back(): ShellNav? = when {
        tab == ShellTab.Settings && page != null -> copy(page = null)
        tab == ShellTab.Settings -> ShellNav()
        else -> null
    }

    // Stored as text because an enum is not saveable without a custom Saver.
    fun encode(): String = "${tab.name}:${page?.name.orEmpty()}"

    companion object {
        fun decode(text: String): ShellNav {
            val (tabName, pageName) = text.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val tab = ShellTab.entries.firstOrNull { it.name == tabName } ?: return ShellNav()
            val page = SettingsPage.entries.firstOrNull { it.name == pageName }
            return if (tab == ShellTab.Settings) ShellNav(tab, page) else ShellNav()
        }
    }
}
