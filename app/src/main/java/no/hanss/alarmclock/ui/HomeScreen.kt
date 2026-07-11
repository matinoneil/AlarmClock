package no.hanss.alarmclock.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.hanss.alarmclock.data.Alarm
import no.hanss.alarmclock.data.AlarmSeries
import no.hanss.alarmclock.data.TimerPreset
import no.hanss.alarmclock.viewmodel.AlarmViewModel

private const val TAB_ALARMS = 0
private const val TAB_TIMERS = 1

/**
 * The app's main screen: a single Scaffold owning the Alarms/Timers tab row
 * (in the top-bar title position), the shared + FAB (whose action depends on
 * the selected tab), and an animated horizontal slide between the two tab
 * bodies. The tab row deliberately lives outside the AnimatedContent so it
 * stays fixed while only the list content slides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AlarmViewModel,
    onAddAlarm: () -> Unit,
    onEditAlarm: (Alarm) -> Unit,
    onAddSeries: () -> Unit,
    onEditSeries: (AlarmSeries) -> Unit,
    onAddTimer: () -> Unit,
    onEditTimer: (TimerPreset) -> Unit,
    onOpenSettings: () -> Unit
) {
    // rememberSaveable so the selected tab survives rotation and returning
    // from an edit screen doesn't dump the user back on Alarms.
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_ALARMS) }
    var showAddMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row {
                        HomeTab("Alarms", selectedTab == TAB_ALARMS) { selectedTab = TAB_ALARMS }
                        Box(Modifier.width(20.dp))
                        HomeTab("Timers", selectedTab == TAB_TIMERS) { selectedTab = TAB_TIMERS }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = {
                        if (selectedTab == TAB_ALARMS) showAddMenu = true else onAddTimer()
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
                // Alarms tab keeps its single-alarm/series chooser; the Timers
                // tab's + goes straight to the timer creation page.
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Outlined.Alarm, contentDescription = null) },
                        text = { Text("Single alarm") },
                        onClick = { showAddMenu = false; onAddAlarm() }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Outlined.Repeat, contentDescription = null) },
                        text = { Text("Alarm series") },
                        onClick = { showAddMenu = false; onAddSeries() }
                    )
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                // Slide in the direction of travel: Alarms -> Timers pushes the
                // content left; going back pushes it right. Paired with a fade
                // so the crossover doesn't look like a hard shove.
                val towardTimers = targetState > initialState
                val enterFrom = if (towardTimers) 1 else -1
                (slideInHorizontally(tween(260)) { it / 3 * enterFrom } + fadeIn(tween(260)))
                    .togetherWith(
                        slideOutHorizontally(tween(260)) { -it / 3 * enterFrom } + fadeOut(tween(200))
                    )
            },
            label = "homeTabContent"
        ) { tab ->
            when (tab) {
                TAB_TIMERS -> TimerListContent(
                    viewModel = viewModel,
                    onEditTimer = onEditTimer
                )
                else -> AlarmListContent(
                    viewModel = viewModel,
                    onEditAlarm = onEditAlarm,
                    onEditSeries = onEditSeries
                )
            }
        }
    }
}

/**
 * One tab title. The selected tab shows at full strength (bold, onSurface);
 * the other sits dimmed until tapped, with the color animating on switch.
 */
@Composable
private fun HomeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        animationSpec = tween(260),
        label = "tabColor"
    )
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = color,
        modifier = Modifier.clickable(
            // No ripple: a ripple box around a bare title looks like a bug.
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    )
}
