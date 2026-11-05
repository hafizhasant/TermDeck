package dev.termdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

// Keeps the process alive while sessions run, so SSH and shells survive leaving the app.
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sessions", "Running sessions", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "sessions")
            .setContentTitle("TermDeck")
            .setContentText("Sessions keep running in the background")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        fun sync(ctx: Context, running: Int) {
            val i = Intent(ctx, SessionService::class.java)
            if (running > 0) ctx.startForegroundService(i) else ctx.stopService(i)
        }
    }
}
