package com.wim4you.intervene.ui.home

import com.google.android.gms.maps.model.LatLng

sealed class RouteState {
    data object Idle : RouteState()
    data object Loading : RouteState()
    data class Success(
        val points: List<LatLng>,
        val destination: LatLng,
        val summary: String,
    ) : RouteState()

    data class Error(val message: String) : RouteState()
}
