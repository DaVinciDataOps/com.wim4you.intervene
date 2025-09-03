package com.wim4you.intervene.fbdata

data class DistressLocationData (
    var id: String,
    var personId: String,
    var location: Map<String,Double> = mapOf("latitude" to 0.0, "longitude" to 0.0),
    var time: Long = System.currentTimeMillis(),
    var fcmToken: String? = null
)