package com.wim4you.intervene.distress

import android.content.Context
import android.widget.Toast
import com.google.firebase.messaging.FirebaseMessaging
import com.wim4you.intervene.SecureLog

object DistressMessagingManager {

    public val topic = "distress_message"
    private const val TAG = "FirebaseMessagingManager"

    fun subscribeToTopic(context: Context) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener { task ->
                val msg =
                    if (task.isSuccessful) "Subscribed to $topic" else "Subscription to $topic failed"
                SecureLog.d(TAG, msg)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
    }

    fun unsubscribeFromTopic(context: Context) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnCompleteListener { task ->
                val msg =
                    if (task.isSuccessful) "Unsubscribed from $topic" else "Unsubscription from $topic failed"
                SecureLog.d(TAG, msg)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
    }
}