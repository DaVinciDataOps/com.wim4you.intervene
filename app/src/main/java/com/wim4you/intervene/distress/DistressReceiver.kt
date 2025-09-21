package com.wim4you.intervene.distress

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wim4you.intervene.IDistressUpdateListener
import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.location.LocationTrackerService.Companion.ACTION_DISTRESS_UPDATE
import com.wim4you.intervene.location.LocationTrackerService.Companion.EXTRA_DISTRESS_DATA

class DistressReceiver(private val listener: IDistressUpdateListener) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_DISTRESS_UPDATE) {
            @Suppress("DEPRECATION")
            val distressLocationList = intent.getParcelableArrayListExtra<DistressLocationData>(EXTRA_DISTRESS_DATA)
            if (!distressLocationList.isNullOrEmpty()) {
                val distressCallList = distressLocationList.map { locationData ->
                    DistressCallData(
                        alias = locationData.alias,
                        address = locationData.address
                    )
                }
                // Notify via callback
                listener.onDistressCallsUpdated(distressCallList)
            }
        }
    }

}