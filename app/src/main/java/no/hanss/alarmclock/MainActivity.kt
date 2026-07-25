package no.hanss.alarmclock

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import no.hanss.alarmclock.ui.AlarmEditScreen
import no.hanss.alarmclock.ui.HomeScreen
import no.hanss.alarmclock.ui.ReminderEditScreen
import no.hanss.alarmclock.ui.SeriesEditScreen
import no.hanss.alarmclock.ui.SettingsScreen
import no.hanss.alarmclock.ui.TimerEditScreen
import no.hanss.alarmclock.ui.theme.AlarmClockTheme
import no.hanss.alarmclock.viewmodel.AlarmViewModel

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: AlarmViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // Android 14+ revokes USE_FULL_SCREEN_INTENT on updates of sideloaded
    // apps (#66); this drives the home-screen banner rather than an
    // auto-opened settings screen. Refreshed in onResume so the banner
    // disappears the moment the user returns from re-enabling it.
    private var fullScreenRevoked by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        fullScreenRevoked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    }

    /**
     * Opens a system settings screen, tolerating its absence. Not every OEM build
     * ships every one of these (the exact-alarm and full-screen-intent screens are
     * the usual offenders), and an unguarded startActivity there means
     * ActivityNotFoundException -- i.e. the app crashes on launch over a permission
     * it only needs for a degraded-but-working feature. Never worth a crash.
     */
    private fun safeStartActivity(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No activity available for ${intent.action}", e)
            false
        }

    /**
     * Requests the FIRST still-missing permission and stops, ordered by how
     * alarm-critical it is; the next one comes up on the next launch. Firing several
     * back-to-back stacks settings screens on top of each other (entries #15, #22).
     */
    private fun requestNextMissingPermission() {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)

        // POST_NOTIFICATIONS is the first link. Capped at two attempts: after two
        // denials Android stops showing the dialog at all -- launch() no-ops straight
        // to a denied callback -- and without the cap the chain would stall here
        // forever, never reaching the settings screens below.
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val permPrefs = getSharedPreferences("permission_flow", MODE_PRIVATE)
        val notificationAsks = permPrefs.getInt("notification_permission_asks", 0)

        if (!notificationsGranted && notificationAsks < 2) {
            permPrefs.edit().putInt("notification_permission_asks", notificationAsks + 1).apply()
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!viewModel.canScheduleExactAlarms() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeStartActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } else if (!notificationManager.isNotificationPolicyAccessGranted) {
            // Changing the alarm volume for the ramp feature throws a SecurityException
            // on many devices if Do Not Disturb/a focus mode is active and this
            // permission hasn't been granted -- the ramp then silently falls back to a
            // plain, non-ramped alarm. Manual per-app toggle, not a runtime dialog.
            safeStartActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } else if (!Settings.canDrawOverlays(this)) {
            // "Display over other apps" lets the ringing screen draw over whatever the
            // user is doing even when the phone is unlocked and actively in use, which
            // the full-screen-intent notification alone can't guarantee (Android
            // downgrades those to heads-up then).
            safeStartActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only on a genuinely new launch. This used to live in a
        // LaunchedEffect(Unit) inside setContent, which re-runs on every activity
        // recreation -- so a rotation re-fired the chain and threw up a settings
        // screen again (entry #71j).
        if (savedInstanceState == null) requestNextMissingPermission()

        setContent {
            // Entry #76: the stretch overscroll at a list edge has to be "paid
            // back" before the container scrolls again -- OverscrollEffect consumes
            // scroll delta BEFORE the list sees it, and subtracts the outstanding
            // overscroll first when the drag reverses. Reaching the bottom and
            // immediately swiping up therefore spent the first part of the gesture
            // discharging the spring instead of scrolling. null disables it for
            // every scrollable below here: the tabs, Settings and all the editors.
            // Purely visual -- no scheduling, DB, service or notification code.
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            AlarmClockTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "list") {
                    composable("list") {
                        HomeScreen(
                            viewModel = viewModel,
                            onAddAlarm = { navController.navigate("alarm_edit/-1") },
                            onEditAlarm = { navController.navigate("alarm_edit/${it.id}") },
                            onAddSeries = { navController.navigate("series_edit/-1") },
                            onEditSeries = { navController.navigate("series_edit/${it.id}") },
                            onAddTimer = { navController.navigate("timer_edit/-1") },
                            onEditTimer = { navController.navigate("timer_edit/${it.id}") },
                            fullScreenRevoked = fullScreenRevoked,
                            onFixFullScreen = {
                                safeStartActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                        data = Uri.parse("package:$packageName")
                                    }
                                )
                            },
                            onAddReminder = { navController.navigate("reminder_edit/-1") },
                            onEditReminder = { navController.navigate("reminder_edit/${it.id}") },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable(
                        route = "alarm_edit/{alarmId}",
                        arguments = listOf(navArgument("alarmId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val alarmId = backStackEntry.arguments?.getLong("alarmId") ?: -1L
                        AlarmEditScreen(
                            alarmId = alarmId,
                            viewModel = viewModel,
                            onDone = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "series_edit/{seriesId}",
                        arguments = listOf(navArgument("seriesId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val seriesId = backStackEntry.arguments?.getLong("seriesId") ?: -1L
                        SeriesEditScreen(
                            seriesId = seriesId,
                            viewModel = viewModel,
                            onDone = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onDone = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "timer_edit/{timerId}",
                        arguments = listOf(navArgument("timerId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val timerId = backStackEntry.arguments?.getLong("timerId") ?: -1L
                        TimerEditScreen(
                            timerId = timerId,
                            viewModel = viewModel,
                            onDone = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "reminder_edit/{reminderId}",
                        arguments = listOf(navArgument("reminderId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val reminderId = backStackEntry.arguments?.getLong("reminderId") ?: -1L
                        ReminderEditScreen(
                            reminderId = reminderId,
                            viewModel = viewModel,
                            onDone = { navController.popBackStack() }
                        )
                    }
                }
            }
            }
        }
    }
}
