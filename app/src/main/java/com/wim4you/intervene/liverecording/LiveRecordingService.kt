package com.wim4you.intervene.liverecording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.Constants
import com.wim4you.intervene.R

/**
 * Foreground notification holder while the capture screen is recording.
 * Camera preview and capture run in [LiveRecordingCaptureFragment].
 */
class LiveRecordingService : Service() {
    private val channelId = Constants.LIVE_RECORDING_SERVICE_CHANNEL_ID
    private val notificationId = Constants.LIVE_RECORDING_SERVICE_NOTIFICATION_ID

    override fun onCreate() {
        super.onCreate()
        LiveRecordingController.bindContext(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppModeController.isGuidedTrip) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(notificationId, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, channelId)
        .setContentTitle(getString(R.string.live_recording_notification_title))
        .setContentText(getString(R.string.live_recording_notification_text))
        .setSmallIcon(R.drawable.ic_menu_camera)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            getString(R.string.live_recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
