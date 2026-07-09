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
import no.hanss.alarmclock.ui.AlarmListScreen
import no.hanss.alarmclock.ui.SeriesEditScreen
import no.hanss.alarmclock.ui.theme.AlarmClockTheme
import no.hanss.alarmclock.viewmodel.AlarmViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AlarmViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AlarmClockTheme {
                val navController = rememberNavController()

                LaunchedEffect(Unit) {
                    if (!viewModel.canScheduleExactAlarms() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }

                    // Android 14+ lets the user revoke full-screen-intent notifications
                    // per app. Without it, alarms fall back to a plain heads-up notification
                    // instead of the full-screen ringing UI.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val notificationManager =
                            getSystemService(android.app.NotificationManager::class.java)
                        if (!notificationManager.canUseFullScreenIntent()) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        }
                    }

                    // Changing the alarm volume for the ramp feature throws a
                    // SecurityException on many devices if Do Not Disturb/a focus
                    // mode is active and this permission hasn't been granted -- the
                    // ramp then silently falls back to a plain, non-ramped alarm.
                    // This is a manual per-app toggle in system settings, not a
                    // runtime permission dialog, same as the overlay permission below.
                    val notificationManager =
                        getSystemService(android.app.NotificationManager::class.java)
                    if (!notificationManager.isNotificationPolicyAccessGranted) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        startActivity(intent)
                    }

                    // "Display over other apps" lets the ringing screen draw over
                    // whatever the user is doing even when the phone is unlocked and
                    // actively in use, which the full-screen-intent notification alone
                    // can't guarantee (Android downgrades those to heads-up in that case).
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }

                NavHost(navController = navController, startDestination = "list") {
                    composable("list") {
                        AlarmListScreen(
                            viewModel = viewModel,
                            onAddAlarm = { navController.navigate("alarm_edit/-1") },
                            onEditAlarm = { navController.navigate("alarm_edit/${it.id}") },
                            onAddSeries = { navController.navigate("series_edit/-1") },
                            onEditSeries = { navController.navigate("series_edit/${it.id}") }
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
                }
            }
        }
    }
}
