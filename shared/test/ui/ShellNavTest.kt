package app.servicecalendar.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellNavTest {
    @Test
    fun startsOnCalendar() {
        assertEquals(ShellTab.Calendar, ShellNav().tab)
    }

    @Test
    fun backFromSubPageGoesToSettingsList() {
        val nav = ShellNav().selectTab(ShellTab.Settings).openPage(SettingsPage.Places)
        assertEquals(ShellNav(ShellTab.Settings, null), nav.back())
    }

    @Test
    fun backFromSettingsListGoesToCalendar() {
        assertEquals(ShellNav(ShellTab.Calendar, null), ShellNav(ShellTab.Settings).back())
    }

    @Test
    fun backOnCalendarIsLeftToSystem() {
        assertNull(ShellNav().back())
    }

    @Test
    fun tappingGearOnSubPageReturnsToSettingsList() {
        val nav = ShellNav().openPage(SettingsPage.Backup).selectTab(ShellTab.Settings)
        assertEquals(ShellNav(ShellTab.Settings, null), nav)
    }

    @Test
    fun calendarThenSettingsReturnsToSettingsList() {
        val nav = ShellNav().openPage(SettingsPage.Reports).selectTab(ShellTab.Calendar).selectTab(ShellTab.Settings)
        assertEquals(ShellNav(ShellTab.Settings, null), nav)
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val all = listOf(ShellNav(), ShellNav(ShellTab.Settings)) + SettingsPage.entries.map { ShellNav().openPage(it) }
        all.forEach { assertEquals(it, ShellNav.decode(it.encode())) }
    }

    @Test
    fun decodeGarbageFallsBackToCalendar() {
        assertEquals(ShellNav(), ShellNav.decode("???"))
        assertEquals(ShellNav(), ShellNav.decode("Calendar:Places"))
    }
}
