package com.wim4you.intervene.location

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.fbdata.PatrolData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LocationService : Service() {

    private val database = FirebaseDatabase.getInstance().reference.child("vigilanteLoc")
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var locationListener: ValueEventListener? = null

    companion object {
        const val ACTION_LOCATION_UPDATE = "com.wim4you.intervene.LOCATION_UPDATE"
        const val EXTRA_PATROL_DATA = "extra_patrol_data"
    }

    override fun onCreate() {
        super.onCreate()
        startListeningForLocations()
    }

    private fun startListeningForLocations() {
        locationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val patrolDataList = mutableListOf<PatrolData>()
                for (child in snapshot.children) {
                    val patrolData = child.getValue(PatrolData::class.java)
                    patrolData?.let {
                        if (it.IsActive == true) { // Only include active patrols
                            patrolDataList.add(it)
                        }
                    }
                }
                // Broadcast the list of active patrol data
                sendLocationUpdate(patrolDataList)
            }

            override fun onCancelled(error: DatabaseError) {
                // Log error or handle as needed (e.g., notify UI of failure)
            }
        }
        database.addValueEventListener(locationListener!!)
    }

    private fun sendLocationUpdate(patrolDataList: List<PatrolData>) {
        val intent = Intent(ACTION_LOCATION_UPDATE)
        intent.putParcelableArrayListExtra(EXTRA_PATROL_DATA, ArrayList(patrolDataList))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}