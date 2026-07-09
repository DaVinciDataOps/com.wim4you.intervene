package com.wim4you.intervene.helpers

import android.app.ActivityManager
import android.content.Context

object ServiceUtils {

    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { service ->
            service.service.className == serviceClass.name
        }
    }
}
