package com.wim4you.intervene.ui.home

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.R
import com.wim4you.intervene.ThemePreferences
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.helpers.GoogleMapMarkers
import com.wim4you.intervene.helpers.TimestampConverter

class HomeMapOverlay(
    private val context: Context,
    private val map: GoogleMap,
) {
    private val patrolMarkerBitmap: Bitmap = GoogleMapMarkers.patrolMarker
    private val myPatrolMarkerBitmap: Bitmap = GoogleMapMarkers.myPatrolMarker
    private val distressMarkerBitmap: Bitmap = GoogleMapMarkers.distressMarker

    private val patrolMarkers = mutableMapOf<String, Marker>()
    private val distressMarkers = mutableMapOf<String, Marker>()
    private var routePolyline: Polyline? = null
    private var destinationMarker: Marker? = null
    private var highlightedDistressId: String? = null

    fun applyMapStyle() {
        if (!ThemePreferences.isDarkModeActive(context)) return
        MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)
            ?.let(map::setMapStyle)
    }

    fun drawRoute(state: RouteState.Success) {
        clearRoute()
        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(state.points)
                .color(ContextCompat.getColor(context, R.color.route_polyline))
                .width(12f),
        )
        destinationMarker = map.addMarker(
            MarkerOptions()
                .position(state.destination)
                .title(context.getString(R.string.route_destination_marker)),
        )
        val boundsBuilder = LatLngBounds.Builder()
        state.points.forEach(boundsBuilder::include)
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120))
    }

    fun clearRoute() {
        routePolyline?.remove()
        routePolyline = null
        destinationMarker?.remove()
        destinationMarker = null
    }

    fun updatePatrolMarkers(patrolLocationDataList: List<PatrolLocationData>) {
        val currentIds = patrolLocationDataList.mapNotNull { it.vigilanteId ?: it.id }.toSet()
        patrolMarkers.keys.filter { it !in currentIds }.forEach { id ->
            patrolMarkers[id]?.remove()
            patrolMarkers.remove(id)
        }

        patrolLocationDataList.forEach { patrolData ->
            patrolData.l?.let { loc ->
                val latLng = LatLng(loc[0], loc[1])
                val markerId = patrolData.vigilanteId ?: patrolData.id ?: return@let
                val marker = patrolMarkers[markerId]
                if (marker == null) {
                    map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title(patrolData.name ?: context.getString(R.string.map_marker_patrol))
                            .icon(getPatrolIcon(patrolData.vigilanteId)),
                    )?.let { patrolMarkers[markerId] = it }
                } else {
                    marker.position = latLng
                }
            }
        }
    }

    fun updateDistressMarkers(
        distressDataList: List<DistressLocationData>,
        selectedDistressId: String? = highlightedDistressId,
    ): Boolean {
        highlightedDistressId = selectedDistressId
        val currentIds = distressDataList.mapNotNull { it.personId ?: it.id }.toSet()
        var hasNewMarkers = false

        distressMarkers.keys.filter { it !in currentIds }.forEach { id ->
            distressMarkers[id]?.remove()
            distressMarkers.remove(id)
        }

        distressDataList.forEach { distressData ->
            distressData.l?.let { loc ->
                val latLng = LatLng(loc[0], loc[1])
                val markerId = distressData.personId ?: distressData.id ?: return@let
                val marker = distressMarkers[markerId]
                if (marker == null) {
                    map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("${distressData.alias} !HELP!")
                            .snippet(getDistressSnippet(distressData))
                            .icon(BitmapDescriptorFactory.fromBitmap(distressMarkerBitmap))
                            .zIndex(if (markerId == selectedDistressId) 2f else 1f),
                    )?.let {
                        distressMarkers[markerId] = it
                        hasNewMarkers = true
                        if (markerId == selectedDistressId) {
                            it.showInfoWindow()
                        }
                    }
                } else {
                    marker.position = latLng
                    marker.snippet = getDistressSnippet(distressData)
                    marker.zIndex = if (markerId == selectedDistressId) 2f else 1f
                    if (markerId == selectedDistressId) {
                        marker.showInfoWindow()
                    }
                }
            }
        }
        return hasNewMarkers
    }

    fun focusOnDistress(distressId: String, latitude: Double, longitude: Double) {
        highlightedDistressId = distressId
        val latLng = LatLng(latitude, longitude)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        distressMarkers[distressId]?.showInfoWindow()
    }

    private fun getPatrolIcon(vigilanteId: String?): BitmapDescriptor {
        val myId = AppModeController.vigilante?.id
        return if (vigilanteId != null && myId != null && vigilanteId == myId) {
            BitmapDescriptorFactory.fromBitmap(myPatrolMarkerBitmap)
        } else {
            BitmapDescriptorFactory.fromBitmap(patrolMarkerBitmap)
        }
    }

    private fun getDistressSnippet(distressData: DistressLocationData): String {
        val currentTimestamp = System.currentTimeMillis()
        val lap = TimestampConverter.lapSeconds(distressData.startTime, currentTimestamp).toString()
        val start = TimestampConverter.toTime(distressData.startTime)
        return context.getString(R.string.map_distress_snippet, start, lap)
    }
}
