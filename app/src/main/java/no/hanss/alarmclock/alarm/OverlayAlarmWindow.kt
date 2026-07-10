package no.hanss.alarmclock.alarm

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 *
 * Visuals intentionally mirror RingingScreen (dark gradient, oversized time,
 * white pill dismiss, quiet snooze). Pure view construction only -- no OS calls
 * beyond the addView/removeView this class always did, per the "never crash
 * rather than ring" rule.
 */
class OverlayAlarmWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun show(timeLabel: String, label: String, snoozeLabel: String, onDismiss: () -> Unit, onSnooze: () -> Unit, showSnooze: Boolean = true) {
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

        // Warm amber-to-dark vertical gradient, matching the Compose ringing screen.
        val backgroundGradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#7A4A10"), Color.parseColor("#3B2405"))
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = backgroundGradient
            setPadding(dp(28), dp(32), dp(28), dp(40))
        }

        val timeView = TextView(context).apply {
            text = timeLabel
            textSize = 80f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 22f
            setTextColor(Color.argb(217, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(56))
        }
        val dismissButton = Button(context).apply {
            text = "Dismiss"
            textSize = 20f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#3B2405"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(36).toFloat()
                setColor(Color.WHITE)
            }
            setOnClickListener { onDismiss() }
        }
        val snoozeButton = Button(context).apply {
            text = snoozeLabel
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.argb(230, 255, 255, 255))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.argb(102, 255, 255, 255))
            }
            setOnClickListener { onSnooze() }
        }

        container.addView(timeView)
        container.addView(labelView)
        container.addView(
            dismissButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72))
        )
        // Timers are dismiss-only; snooze has no countdown meaning.
        if (showSnooze) {
            container.addView(
                snoozeButton,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply {
                    topMargin = dp(14)
                }
            )
        }

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
