package com.sanamtamang.flutter_wireguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.flutter.embedding.android.FlutterActivity

class VpnKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "VpnPersistentChannel"
        private const val NOTIFICATION_ID = 1984
        private const val ACTION_STOP_VPN = "com.sanamtamang.flutter_wireguard.STOP_VPN"
        private const val TAG = "VpnKeepAliveService"
        private const val DEFAULT_NOTIFICATION_TITLE = "Your internet is private"
        private const val DEFAULT_NOTIFICATION_BODY = "Tap to open settings"
        const val EXTRA_NOTIFICATION_TITLE = "notification_title"
        const val EXTRA_NOTIFICATION_BODY = "notification_body"
    }

    private var notificationTitle: String = DEFAULT_NOTIFICATION_TITLE
    private var notificationBody: String = DEFAULT_NOTIFICATION_BODY

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VPN) {
            // ✅ Disconnect VPN directly from service
            Log.i(TAG, "Stop VPN action received from notification")

            // Try to disconnect via plugin instance first (if app is running)
            val plugin = FlutterWireguardPlugin.getInstance()
            if (plugin != null) {
                Log.i(TAG, "Plugin instance found, disconnecting via plugin")
                plugin.disconnectVpnFromService()
            } else {
                Log.i(TAG, "Plugin instance not found, sending broadcast for fallback")
                // Fallback: send broadcast in case plugin is still listening
                val disconnectIntent = Intent("com.sanamtamang.flutter_wireguard.STOP_VPN_REQUEST")
                LocalBroadcastManager.getInstance(this).sendBroadcast(disconnectIntent)
            }

            // Stop the service after disconnecting
            stopSelf()
            return START_NOT_STICKY
        }

        // Extract custom notification values from intent extras
        intent?.let {
            notificationTitle = it.getStringExtra(EXTRA_NOTIFICATION_TITLE) ?: DEFAULT_NOTIFICATION_TITLE
            notificationBody = it.getStringExtra(EXTRA_NOTIFICATION_BODY) ?: DEFAULT_NOTIFICATION_BODY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Secure Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when your private tunnel is active"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, FlutterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ✅ Create "Stop" action that sends broadcast to disconnect VPN
        val stopIntent = Intent(this, VpnKeepAliveService::class.java).apply {
            action = ACTION_STOP_VPN
        }

        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationBody)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
} 