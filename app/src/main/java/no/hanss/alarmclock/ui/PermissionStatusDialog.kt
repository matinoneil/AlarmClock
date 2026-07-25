package no.hanss.alarmclock.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private const val TAG = "PermissionStatus"

/** Green reads as "fine" in both light and dark; missing uses the theme error colour. */
private val GrantedGreen = Color(0xFF2E9B57)

/**
 * One inspectable permission. [intent] is null when there is nothing to open --
 * either it is already granted, or the OS version does not let the user control it.
 */
private data class PermissionRow(
    val label: String,
    val why: String,
    val granted: Boolean,
    val intent: Intent?
)

/**
 * Everything the app depends on that a user can revoke, with the same checks
 * MainActivity.requestNextMissingPermission() uses -- deliberately not
 * reimplemented, so the dots can never disagree with the request chain.
 *
 * Version gating cuts both ways: below the API level that introduced each
 * restriction the permission is not user-revocable, so it must read as GRANTED.
 * Reporting it missing would show a red dot on every older device for something
 * the user cannot act on. minSdk here is 26.
 */
private fun currentPermissionRows(context: Context): List<PermissionRow> {
    val nm = context.getSystemService(NotificationManager::class.java)
    val pkg = context.packageName

    fun packageIntent(action: String) = Intent(action).apply { data = Uri.parse("package:$pkg") }

    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    // Mirrors MainActivity.onResume's shape exactly (>= && !granted) rather than
    // inverting it, because that form is already proven to compile and lint clean
    // against an API 34 method with minSdk 26.
    val fullScreenRevoked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        !nm.canUseFullScreenIntent()

    // SDK check FIRST so the short-circuit stops us calling an API 31 method on
    // API 26-30, where it does not exist. See the note in entry #77.
    val exactGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    return listOf(
        PermissionRow(
            "Notifications",
            "Alarms, timers and reminders are all delivered as notifications. Without this the app can still ring but shows nothing.",
            notificationsGranted,
            if (notificationsGranted) null else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        ),
        PermissionRow(
            "Exact alarms",
            "Lets an alarm fire at its exact minute instead of being batched into a system maintenance window.",
            exactGranted,
            if (exactGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
            else packageIntent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        ),
        PermissionRow(
            "Full-screen alarms",
            "Lets a ringing alarm take over the screen. Android switches this off after some updates; without it alarms ring as ordinary notifications.",
            !fullScreenRevoked,
            if (!fullScreenRevoked || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) null
            else packageIntent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        ),
        PermissionRow(
            "Display over other apps",
            "Lets the ringing screen appear while you are actively using the phone, which a full-screen notification alone cannot guarantee.",
            Settings.canDrawOverlays(context),
            if (Settings.canDrawOverlays(context)) null
            else packageIntent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        ),
        PermissionRow(
            "Do Not Disturb access",
            "Needed for the volume ramp while a focus mode is on. Without it the ramp falls back to a plain alarm at fixed volume.",
            nm.isNotificationPolicyAccessGranted,
            if (nm.isNotificationPolicyAccessGranted) null
            else Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        )
    )
}

/**
 * Read-only status box for the permissions this app degrades quietly without
 * (entries 0.1, #66, #77). Re-checks on every resume, so returning from a
 * settings screen updates the dots without reopening the dialog.
 */
@Composable
fun PermissionStatusDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var rows by remember { mutableStateOf(currentPermissionRows(context)) }
    var unavailable by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) rows = currentPermissionRows(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grantedCount = rows.count { it.granted }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    if (grantedCount == rows.size) {
                        "All ${rows.size} granted. Nothing to do."
                    } else {
                        "$grantedCount of ${rows.size} granted. Tap one to open its Android settings screen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                rows.forEach { row ->
                    val clickable = row.intent != null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (clickable) {
                                    Modifier.clickable {
                                        // Never an unguarded startActivity: OEM builds
                                        // routinely lack the exact-alarm and
                                        // full-screen-intent screens, and an inspector
                                        // that crashes is worse than no inspector.
                                        // Same reasoning as MainActivity.safeStartActivity.
                                        try {
                                            context.startActivity(row.intent)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "No activity for ${row.intent?.action}", e)
                                            unavailable = unavailable + row.label
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Spacer(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(10.dp)
                                .background(
                                    color = if (row.granted) GrantedGreen
                                    else MaterialTheme.colorScheme.error,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(row.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                row.why,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (row.label in unavailable) {
                                Text(
                                    "This phone has no settings screen for it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (clickable) {
                                Text(
                                    "Tap to fix",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
