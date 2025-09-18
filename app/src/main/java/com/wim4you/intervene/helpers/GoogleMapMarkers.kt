package com.wim4you.intervene.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.wim4you.intervene.R

object GoogleMapMarkers {
        lateinit var patrolMarker: Bitmap
        lateinit var myPatrolMarker: Bitmap
        lateinit var distressMarker: Bitmap

        fun initialize(context: Context) {
            patrolMarker = BitmapFactory.decodeResource(context.resources, R.drawable.png_patrol_marker)
                .scale(90, 90, false)
            myPatrolMarker = BitmapFactory.decodeResource(context.resources, R.drawable.png_my_patrol_marker)
                .scale(90, 90, false)
            distressMarker = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
                .scale(90, 90, false)
        }
}