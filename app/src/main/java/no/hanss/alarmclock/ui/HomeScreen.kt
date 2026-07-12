package no.hanss.alarmclock.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
 * the selected tab), and a HorizontalPager between the two tab bodies --
 * tapping a tab animates the pager, and swiping the content drags it, one
 * shared state either way. The tab row deliberately lives outside the pager
 * so it stays fixed while only the list content slides.
 */
// ExperimentalFoundationApi: Pager is still experimental in foundation 1.6.8
// (this BOM); it stabilizes in 1.7 (entry #41).
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // Pager state is the single source of truth for which tab is showing;
    // it's saveable, so rotation and returning from an edit screen keep the
    // tab. Tabs tap-scroll it, swipes drag it -- same state either way.
    val pagerState = rememberPagerState(initialPage = TAB_ALARMS, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    var showAddMenu by remember { mutableStateOf(false) }
    // targetPage flips as a swipe crosses the halfway point, so the tab
    // highlight answers mid-drag instead of only after the settle.
    val selectedTab = pagerState.targetPage

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row {
                        HomeTab("Alarms", selectedTab == TAB_ALARMS) {
                            scope.launch { pagerState.animateScrollToPage(TAB_ALARMS) }
                        }
                        Box(Modifier.width(20.dp))
                        HomeTab("Timers", selectedTab == TAB_TIMERS) {
                            scope.launch { pagerState.animateScrollToPage(TAB_TIMERS) }
                        }
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
                        if (pagerState.currentPage == TAB_ALARMS) showAddMenu = true else onAddTimer()
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
        // The pager both animates tab taps and follows finger drags; vertical
        // list scrolling inside the pages is disambiguated by the pager's own
        // orientation locking.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { page ->
            when (page) {
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
