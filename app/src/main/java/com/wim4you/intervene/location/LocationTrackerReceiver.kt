package com.wim4you.intervene.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wim4you.intervene.SecureLog

class LocationTrackerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        SecureLog.w("LocationTrackerReceiver", "Ignoring unexpected broadcast: ${intent?.action}")
    }

}