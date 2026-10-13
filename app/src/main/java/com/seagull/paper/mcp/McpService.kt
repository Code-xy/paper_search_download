package com.seagull.paper.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.seagull.paper.MainActivity
import com.seagull.paper.data.Store

/**
 * MCP 服务的前台保活。Android 会杀掉后台 socket，所以必须挂前台通知。
 */
class McpService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            McpHub.stop()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val store = Store(this)
        val port = store.mcpPort
        val token = store.mcpToken
        val ok = McpHub.start(application as android.app.Application, port, token)
        startForegroundCompat(port, ok)
        return START_STICKY
    }

    override fun onDestroy() {
        McpHub.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "MCP 服务", NotificationManager.IMPORTANCE_LOW)
        ch.description = "内嵌 MCP 服务器运行状态"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundCompat(port: Int, ok: Boolean) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (ok) "学术搜 · MCP 服务运行中" else "学术搜 · MCP 启动失败")
            .setContentText(if (ok) "http://127.0.0.1:$port/mcp" else McpHub.state.value.error)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        private const val CHANNEL = "mcp_service"
        private const val NOTIF_ID = 8801
        const val ACTION_STOP = "com.seagull.paper.mcp.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, McpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, McpService::class.java))
        }
    }
}
