package com.wim4you.intervene.vigilantes

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.wim4you.intervene.SecureLog

class VigilanteService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SecureLog.w("VigilanteService", "VigilanteService is not implemented yet; stopping service")
        stopSelf()
        return START_NOT_STICKY
    }

}