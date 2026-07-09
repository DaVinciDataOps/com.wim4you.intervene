package com.wim4you.intervene.distress

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.wim4you.intervene.SecureLog

object DistressMessagingManager {

    const val topic = "distress_message"
    private const val TAG = "FirebaseMessagingManager"

    fun subscribeToTopic(context: Context) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    SecureLog.i(TAG, "Subscribed to $topic")
                } else {
                    SecureLog.e(TAG, "Subscription to $topic failed", task.exception)
                }
            }
    }

    fun unsubscribeFromTopic(context: Context) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    SecureLog.i(TAG, "Unsubscribed from $topic")
                } else {
                    SecureLog.e(TAG, "Unsubscription from $topic failed", task.exception)
                }
            }
    }
}
