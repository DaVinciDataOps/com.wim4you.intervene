package com.wim4you.intervene.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
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
            distressMarker = drawableToBitmap(context, R.drawable.ic_assistance, 90)
        }

        private fun drawableToBitmap(context: Context, drawableRes: Int, size: Int): Bitmap {
            val drawable = AppCompatResources.getDrawable(context, drawableRes)
                ?: return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val tinted = DrawableCompat.wrap(drawable.mutate())
            DrawableCompat.setTint(tinted, context.getColor(R.color.color_distress))
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            tinted.setBounds(0, 0, size, size)
            tinted.draw(canvas)
            return bitmap
        }
}