package com.wim4you.intervene

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionsUtils {
    fun hasForegroundLocationPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundLocationPermission(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasForegroundServiceLocationPermission(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun foregroundLocationPermissions(): Array<String> {
        val permissions = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        return permissions.toTypedArray()
    }

    fun backgroundLocationPermissions(): Array<String> {
        return arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun shouldShowForegroundLocationRationale(activity: Activity): Boolean {
        return activity.shouldShowRequestPermissionRationale(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    fun shouldShowBackgroundLocationRationale(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.shouldShowRequestPermissionRationale(
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        } else {
            false
        }
    }

    fun needsBackgroundLocationPermission(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasForegroundLocationPermission(activity) &&
            !hasBackgroundLocationPermission(activity)
    }
}
