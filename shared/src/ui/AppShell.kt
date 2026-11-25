package app.servicecalendar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.servicecalendar.ui.settings.SettingsScreen
import app.servicecalendar.ui.settings.ShellHeader
import app.servicecalendar.ui.theme.AppIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.servicecalendar.domain.parseStorageDate
import app.servicecalendar.domain.toStorageText
import app.servicecalendar.ui.calendar.CalendarScreen
import app.servicecalendar.ui.backup.BackupScreen
import app.servicecalendar.ui.catalog.PlaceEditorScreen
import app.servicecalendar.ui.catalog.PlacesScreen
import app.servicecalendar.ui.catalog.ProceduresScreen
import app.servicecalendar.ui.entry.DayEntryScreen
import app.servicecalendar.ui.entry.PlacePickerSheet
import app.servicecalendar.ui.report.ReportScreen

@Composable
fun AppShell() {
    // Navigation and dates are saved as text: enums and LocalDate are not saveable, and text is time-zone independent.
    var navText by rememberSaveable { mutableStateOf(ShellNav().encode()) }
    val nav = ShellNav.decode(navText)
    fun navigate(next: ShellNav) {
        navText = next.encode()
    }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorPlaceId by rememberSaveable { mutableStateOf<Long?>(null) }

    var entryDateText by rememberSaveable { mutableStateOf<String?>(null) }
    var entryPlaceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerDateText by rememberSaveable { mutableStateOf<String?>(null) }

    if (editorOpen) {
        PlaceEditorScreen(placeId = editorPlaceId, onClose = { editorOpen = false })
        return
    }

    val entryDate = entryDateText?.let(::parseStorageDate)
    val entryPlace = entryPlaceId
    if (entryDate != null && entryPlace != null) {
        DayEntryScreen(
            date = entryDate,
            placeId = entryPlace,
            onClose = {
                entryDateText = null
                entryPlaceId = null
            },
        )
        return
    }

    PlatformBackHandler(enabled = nav.back() != null) { nav.back()?.let(::navigate) }

    Scaffold(
        bottomBar = { ShellBottomBar(selected = nav.tab, onSelect = { navigate(nav.selectTab(it)) }) },
    ) { padding ->
        pickerDateText?.let(::parseStorageDate)?.let { pickerDate ->
            PlacePickerSheet(
                date = pickerDate,
                onPick = { placeId ->
                    entryDateText = pickerDate.toStorageText()
                    entryPlaceId = placeId
                    pickerDateText = null
                },
                onDismiss = { pickerDateText = null },
            )
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (nav.tab) {
                ShellTab.Calendar -> CalendarScreen(
                    onAddEntry = { date -> pickerDateText = date.toStorageText() },
                    onOpenEntry = { date, placeId ->
                        entryDateText = date.toStorageText()
                        entryPlaceId = placeId
                    },
                )

                ShellTab.Settings -> {
                    val page = nav.page
                    if (page == null) {
                        SettingsScreen(onOpen = { navigate(nav.openPage(it)) })
                    } else {
                        SettingsSubPage(title = ShellStrings.title(page), onBack = { navigate(nav.back() ?: nav) }) {
                            when (page) {
                                SettingsPage.Reports -> ReportScreen()
                                SettingsPage.Places -> PlacesScreen(
                                    onOpenPlace = { id ->
                                        editorPlaceId = id
                                        editorOpen = true
                                    },
                                )
                                SettingsPage.Procedures -> ProceduresScreen()
                                SettingsPage.Backup -> BackupScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ShellHeader(title = title, onBack = onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun ShellBottomBar(selected: ShellTab, onSelect: (ShellTab) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(56.dp)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarItem(AppIcons.CalendarMonth, ShellStrings.TAB_CALENDAR, selected == ShellTab.Calendar) { onSelect(ShellTab.Calendar) }
            BarItem(AppIcons.Settings, ShellStrings.TAB_SETTINGS, selected == ShellTab.Settings) { onSelect(ShellTab.Settings) }
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onPrimary.let { if (selected) it else it.copy(alpha = 0.55f) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}
