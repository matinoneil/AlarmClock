package no.hanss.alarmclock.alarm

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draws a full-screen alarm UI directly over whatever the user is currently doing.
 * This is what makes the ringing screen reliably appear even when the device is
 * unlocked and actively in use -- a full-screen-intent notification alone can't
 * guarantee that, since Android downgrades those to a plain heads-up notification
 * whenever the screen is already on. Requires the "display over other apps" (
 * SYSTEM_ALERT_WINDOW) permission; callers should check Settings.canDrawOverlays()
 * before calling [show].
 */
class OverlayAlarmWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null

    fun show(timeLabel: String, label: String, snoozeLabel: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
        if (rootView != null) return // already showing

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#6650A4"))
            val pad = (32 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val timeView = TextView(context).apply {
            text = timeLabel
            textSize = 56f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val topPad = (16 * context.resources.displayMetrics.density).toInt()
            val bottomPad = (64 * context.resources.displayMetrics.density).toInt()
            setPadding(0, topPad, 0, bottomPad)
        }
        val dismissButton = Button(context).apply {
            text = "Dismiss"
            setOnClickListener { onDismiss() }
        }
        val snoozeButton = Button(context).apply {
            text = snoozeLabel
            setOnClickListener { onSnooze() }
        }

        val buttonMarginTop = (24 * context.resources.displayMetrics.density).toInt()

        container.addView(timeView)
        container.addView(labelView)
        container.addView(
            dismissButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        container.addView(
            snoozeButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = buttonMarginTop
            }
        )

        rootView = container
        windowManager.addView(container, params)
    }

    fun dismiss() {
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
    }
}
