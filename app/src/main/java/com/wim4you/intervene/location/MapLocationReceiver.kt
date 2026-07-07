package com.wim4you.intervene.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.ui.map.MapDataViewModel

class MapLocationReceiver(
    private val mapDataViewModel: MapDataViewModel
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            LocationTrackerService.ACTION_PATROL_UPDATE -> {
                @Suppress("DEPRECATION")
                val patrolList = intent.getParcelableArrayListExtra<PatrolLocationData>(
                    LocationTrackerService.EXTRA_PATROL_DATA
                )
                mapDataViewModel.updatePatrolLocations(patrolList ?: emptyList())
            }
            LocationTrackerService.ACTION_DISTRESS_UPDATE -> {
                @Suppress("DEPRECATION")
                val distressList = intent.getParcelableArrayListExtra<DistressLocationData>(
                    LocationTrackerService.EXTRA_DISTRESS_DATA
                )
                mapDataViewModel.updateDistressLocations(distressList ?: emptyList())
            }
        }
    }
}
