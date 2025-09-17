package com.wim4you.intervene.distress

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wim4you.intervene.MainActivity
import com.wim4you.intervene.R

class DistressMessagingService: FirebaseMessagingService() {
    private val TAG = "DistressMessagingService"

    private fun requestNotificationPermission(context: android.content.Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                (context as? AppCompatActivity)?.let {
                    requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } ?: run {
                    // Handle case where context is not an Activity
                    Log.e("DistressMessaging", "Context is not an Activity, cannot request permission")
                }
            }
        }
    }


    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Handle data payload if present (e.g., custom distress details)
        remoteMessage.data.isNotEmpty().let {
            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
            // Process custom data here, e.g., show custom UI or trigger app logic
        }

        // Handle notification payload (if sent with notification)
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            showNotification(it.title ?: "Distress Message", it.body ?: "Distress message received")
        }

        // If no notification payload but data only, you can create a custom notification
        if (remoteMessage.notification == null && remoteMessage.data.isNotEmpty()) {
            showNotification(remoteMessage.data["title"] ?: "Distress Message", remoteMessage.data["message"] ?: "Distress message received")
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "Refreshed token: $token")
        // Send token to your server if needed for other purposes
    }

    private fun showNotification(title:String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission(this)
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {  // Replace with your main activity
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, DistressMessagingManager.topic)
            .setSmallIcon(R.drawable.ic_notification)  // Replace with your app's icon
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DistressMessagingManager.topic,
                "Distress Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for distress messages"
            }
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0, notificationBuilder.build())
    }
}