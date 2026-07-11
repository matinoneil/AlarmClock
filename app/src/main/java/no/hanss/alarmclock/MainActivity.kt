package no.hanss.alarmclock

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import no.hanss.alarmclock.ui.AlarmEditScreen
import no.hanss.alarmclock.ui.HomeScreen
import no.hanss.alarmclock.ui.SeriesEditScreen
import no.hanss.alarmclock.ui.SettingsScreen
import no.hanss.alarmclock.ui.TimerEditScreen
import no.hanss.alarmclock.ui.theme.AlarmClockTheme
import no.hanss.alarmclock.viewmodel.AlarmViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AlarmViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AlarmClockTheme {
                val navController = rememberNavController()

                LaunchedEffect(Unit) {
                    // One settings screen per app launch (first missing one wins,
                    // ordered by how alarm-critical the permission is) -- firing
                    // several startActivity calls back-to-back stacks the screens on
                    // top of each other, which is disorienting on a fresh install.
                    // The next missing one comes up on the next launch.
                    val notificationManager =
                        getSystemService(android.app.NotificationManager::class.java)
                    // The POST_NOTIFICATIONS runtime dialog is the first link in the
                    // chain (previously it fired unconditionally in onCreate, stacking
                    // on top of the first settings screen on a fresh install). Capped
                    // at two attempts: after two denials Android stops showing the
                    // dialog at all -- launch() just no-ops straight to a denied
                    // callback -- and without the cap the chain would stall here
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
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } else if (
                        // Android 14+ lets the user revoke full-screen-intent
                        // notifications per app. Without it, alarms fall back to a
                        // plain heads-up notification instead of the ringing UI.
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        !notificationManager.canUseFullScreenIntent()
                    ) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } else if (!notificationManager.isNotificationPolicyAccessGranted) {
                        // Changing the alarm volume for the ramp feature throws a
                        // SecurityException on many devices if Do Not Disturb/a focus
                        // mode is active and this permission hasn't been granted -- the
                        // ramp then silently falls back to a plain, non-ramped alarm.
                        // This is a manual per-app toggle in system settings, not a
                        // runtime permission dialog, same as the overlay one below.
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                        // "Display over other apps" lets the ringing screen draw over
                        // whatever the user is doing even when the phone is unlocked and
                        // actively in use, which the full-screen-intent notification alone
                        // can't guarantee (Android downgrades those to heads-up then).
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }

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
                }
            }
        }
    }
}
