package com.wim4you.intervene

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wim4you.intervene.location.LocationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocationUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params){

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val latLng = LocationUtils.setLocation(applicationContext){}
            // You can handle the LatLng result here (e.g., save to database, send to server, etc.)
            Log.d("LocationUpdateWorker", "Location: $latLng")
            Result.success()
        } catch (e: Exception) {
            Log.e("LocationUpdateWorker", "Error getting location: ${e.message}")
            Result.retry() // Retry on failure
        }
    }

}