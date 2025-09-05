package com.wim4you.intervene.location

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LocationService : Service() {

    private val refVigilanteLoc = FirebaseDatabase.getInstance().reference.child("vigilanteLoc")
    private val refDistress = FirebaseDatabase.getInstance().reference.child("distress")
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var locationListener: ValueEventListener? = null
    private var distressListener: ValueEventListener? = null
    private val notificationId = 1002

    companion object {
        const val ACTION_PATROL_UPDATE = "com.wim4you.intervene.LOCATION_UPDATE"
        const val ACTION_DISTRESS_UPDATE = "com.wim4you.intervene.DISTRESS_UPDATE"
        const val EXTRA_PATROL_DATA = "extra_patrol_data"
        const val EXTRA_DISTRESS_DATA = "extra_distress_data"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "LocationServiceChannel"
        private const val THIRTY_MINUTES_IN_MS = 30 * 60 * 1000
    }

    override fun onCreate() {
        super.onCreate()
        // startForegroundService()
        fetchInitialData()
        startListeningForLocations()
        startListeningForDistress()
    }

    private fun fetchInitialData() {
        val thirtyMinutesAgo = System.currentTimeMillis() - THIRTY_MINUTES_IN_MS
        coroutineScope.launch {
            fetchInitialPatrols(thirtyMinutesAgo)
            fetchInitialDistress(thirtyMinutesAgo)
        }
    }

    private suspend fun fetchInitialPatrols(thirtyMinutesAgo: Long) {
        try {
            val patrolSnapshot = refVigilanteLoc
                .orderByChild("time").startAt(thirtyMinutesAgo.toDouble())
                .orderByChild("active").equalTo(true)
                .get().await()

            val patrolDataList = mutableListOf<PatrolData>()
            for (child in patrolSnapshot.children) {
                val patrolData = child.getValue(PatrolData::class.java)
                patrolData?.let {
                    if (it.isActive == true) {
                        patrolDataList.add(it)
                    }
                }
            }
            sendLocationUpdate(patrolDataList)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun fetchInitialDistress(thirtyMinutesAgo: Long) {
        try {
            val distressSnapshot = refDistress
                .orderByChild("time").startAt(thirtyMinutesAgo.toDouble())
                .get().await()

            val distressDataList = mutableListOf<DistressLocationData>()
            for (child in distressSnapshot.children) {
                val distressData = child.getValue(DistressLocationData::class.java)
                distressData?.let {
                    distressDataList.add(it)
                }
            }
            sendDistressUpdate(distressDataList)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startListeningForLocations() {
        locationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val patrolDataList = mutableListOf<PatrolData>()
                for (child in snapshot.children) {
                    val patrolData = child.getValue(PatrolData::class.java)
                    patrolData?.let {
                        if (it.isActive == true) { // Only include active patrols
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
        refVigilanteLoc.addValueEventListener(locationListener!!)
    }

    private fun startListeningForDistress() {
        distressListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val distressDataList = mutableListOf<DistressLocationData>()
                for (child in snapshot.children) {
                    val distressData = child.getValue(DistressLocationData::class.java)
                    distressData?.let {
                        distressDataList.add(it)
                    }
                }
                sendDistressUpdate(distressDataList)
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        }
        refDistress.addValueEventListener(distressListener!!)
    }

    private fun sendLocationUpdate(patrolDataList: List<PatrolData>) {
        val intent = Intent(ACTION_PATROL_UPDATE)
        intent.putParcelableArrayListExtra(EXTRA_PATROL_DATA, ArrayList(patrolDataList))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendDistressUpdate(distressDataList: List<DistressLocationData>) {
        val intent = Intent(ACTION_DISTRESS_UPDATE)
        intent.putParcelableArrayListExtra(EXTRA_DISTRESS_DATA, ArrayList(distressDataList))
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}