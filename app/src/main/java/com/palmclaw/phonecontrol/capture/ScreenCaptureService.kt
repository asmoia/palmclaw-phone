package com.palmclaw.phonecontrol.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.palmclaw.ui.MainActivity
import com.palmclaw.R
import com.palmclaw.phonecontrol.core.Logs
import com.palmclaw.phonecontrol.core.Prefs

/**
 * 持有 MediaProjection 的前台服务。
 * Android 14+ 要求：先 startForeground(mediaProjection) 再 getMediaProjection。
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification("屏幕捕获运行中"))
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_CODE_INVALID)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (code == RESULT_CODE_INVALID || data == null) {
                    Logs.add("缺少投屏授权数据，无法启动捕获")
                    stopSelf()
                    return START_NOT_STICKY
                }

                try {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    val projection = mpm.getMediaProjection(code, data)
                    if (projection == null) {
                        Logs.add("getMediaProjection 返回 null")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    val manager = ScreenCaptureManager(applicationContext)
                    manager.start(projection, Prefs.config.value.maxWidth)
                    instance = manager
                    manager.awaitFirstFrame(2500L)
                } catch (t: Throwable) {
                    Logs.add("启动捕获异常: ${t.message}")
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                instance?.stop()
                instance = null
                stopForegroundCompat()
                stopSelf()
            }

            else -> {
                if (instance == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance?.stop()
        instance = null
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_capture),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = getString(R.string.channel_capture_desc)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.palmclaw)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.palmclaw.phonecontrol.action.START_CAPTURE"
        const val ACTION_STOP = "com.palmclaw.phonecontrol.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val RESULT_CODE_INVALID = Int.MIN_VALUE

        private const val CHANNEL_ID = "apkmcp_capture"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: ScreenCaptureManager? = null
            private set

        fun ready(): Boolean = instance?.running == true

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            try {
                ctx.startService(i)
            } catch (_: Throwable) {
            }
        }
    }
}
