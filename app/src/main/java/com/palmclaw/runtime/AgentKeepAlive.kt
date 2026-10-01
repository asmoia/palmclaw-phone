package com.palmclaw.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.palmclaw.AppContainer
import com.palmclaw.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps an in-flight agent conversation alive when the user switches to
 * another app (EMUI and friends freeze background processes aggressively):
 *
 *  - [AgentKeepAliveService] — foreground service with a visible notification
 *    plus a partial wake lock while the agent is mid-run
 *  - a small draggable "PalmClaw" pill drawn over other apps
 *    (SYSTEM_ALERT_WINDOW) — the visible surface keeps the process out of the
 *    "background" bucket on strict OEMs, and one tap jumps back into the chat
 *  - everything self-stops shortly after the agent goes idle — zero permanent
 *    cost when you're just chatting with the app in the foreground
 *
 * Wiring is deliberately minimal and additive:
 *  - AdaptiveLlmProvider marks model-call boundaries (busy counter)
 *  - AppContainer reports foreground/background (via AgentKeepAlive.setVisible)
 *  - MainActivity asks for overlay + battery-exemption once per install
 */
object AgentKeepAlive {

    /** Stay alive this long after the last model call (agent tool loops have gaps). */
    const val LINGER_MS = 90_000L

    private val activeCalls = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appVisible = true
    @Volatile private var lastActiveAt = 0L
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun chatStarted() {
        activeCalls.incrementAndGet()
        lastActiveAt = System.currentTimeMillis()
        push()
    }

    fun chatFinished() {
        activeCalls.decrementAndGet()
        lastActiveAt = System.currentTimeMillis()
        push()
    }

    fun setVisible(visible: Boolean) {
        appVisible = visible
        push()
    }

    fun isBusy(): Boolean =
        activeCalls.get() > 0 ||
            (lastActiveAt > 0 && System.currentTimeMillis() - lastActiveAt < LINGER_MS)

    fun appVisible(): Boolean = appVisible

    private fun push() {
        main.removeCallbacks(::sync)
        main.postDelayed(::sync, 400) // debounce rapid chat boundaries
    }

    private fun sync() {
        val ctx = appContext ?: return
        if (isBusy()) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, AgentKeepAliveService::class.java))
            } catch (_: Exception) {
                // Android 12+ can refuse background FGS starts; the next
                // foreground-started turn will pick it up.
            }
        } else {
            ctx.stopService(Intent(ctx, AgentKeepAliveService::class.java))
        }
    }

    /**
     * One-time-per-install permission prompts:
     *  - POST_NOTIFICATIONS (13+) so the keep-alive notification is visible
     *  - battery-optimization exemption — critical on Huawei/Xiaomi
     *  - draw-over-other-apps — enables the floating pill
     */
    fun ensurePermissions(activity: android.app.Activity) {
        val ctx = activity.applicationContext
        init(ctx)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4711
            )
        }

        val prefs = ctx.getSharedPreferences("agent_keepalive", Context.MODE_PRIVATE)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!prefs.getBoolean("batteryAsked", false) &&
            !pm.isIgnoringBatteryOptimizations(ctx.packageName)
        ) {
            prefs.edit().putBoolean("batteryAsked", true).apply()
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }
        }
        if (!prefs.getBoolean("overlayAsked", false) && !Settings.canDrawOverlays(ctx)) {
            prefs.edit().putBoolean("overlayAsked", true).apply()
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }
        }
    }
}

/**
 * The foreground service itself. Runs only while [AgentKeepAlive.isBusy].
 */
class AgentKeepAliveService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var overlay: View? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startSpecialUseForeground(buildNotification())
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "palmclaw:agent").apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        }
        main.postDelayed(::recheck, CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentKeepAlive.init(applicationContext)
        updateOverlay()
        return START_STICKY
    }

    /** Self-stop when the agent has been idle past the linger window. */
    private fun recheck() {
        if (!AgentKeepAlive.isBusy()) {
            // Agent finished while the app is backgrounded → perform the
            // gateway release that onAppBackgrounded deferred.
            serviceScope.launch {
                runCatching {
                    (applicationContext as? android.app.Application)
                        ?.let { AppContainer.from(it) }
                        ?.runtimeApplicationService
                        ?.onAgentIdleWhileBackgrounded()
                }
            }
            stopSelf()
            return
        }
        wakeLock?.let { wl -> if (!wl.isHeld) runCatching { wl.acquire(WAKE_TIMEOUT_MS) } }
        updateOverlay()
        main.postDelayed(::recheck, CHECK_INTERVAL_MS)
    }

    // ── floating pill ────────────────────────────────────────────────────────

    private fun updateOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shouldShow = !AgentKeepAlive.appVisible() && Settings.canDrawOverlays(this)
        if (shouldShow && overlay == null) {
            runCatching { addOverlay(wm) }
        } else if (!shouldShow && overlay != null) {
            runCatching { wm.removeView(overlay) }
            overlay = null
        }
    }

    private fun addOverlay(wm: WindowManager) {
        val density = resources.displayMetrics.density
        val pad = (10 * density).toInt()
        val pill = TextView(this).apply {
            text = "● PalmClaw"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(0xF01B2A38.toInt())
            }
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (120 * density).toInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        pill.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(pill, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        runCatching {
                            startActivity(
                                Intent(this@AgentKeepAliveService, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        wm.addView(pill, params)
        overlay = pill
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        overlay?.let {
            runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        overlay = null
        wakeLock?.let { wl -> if (wl.isHeld) wl.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ── notification ─────────────────────────────────────────────────────────

    private fun startSpecialUseForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PalmClaw agent")
            .setContentText("Agent is working — you can switch apps.")
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent keep-alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps an active agent run alive while you use other apps."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "agent_keepalive"
        private const val NOTIFICATION_ID = 9101
        private const val CHECK_INTERVAL_MS = 5_000L
        private const val WAKE_TIMEOUT_MS = 15 * 60_000L
    }
}
