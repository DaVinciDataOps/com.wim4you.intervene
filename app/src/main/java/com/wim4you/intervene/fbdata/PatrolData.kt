package com.wim4you.intervene.fbdata

data class PatrolData (
    var id: String,
    var vigilanteId: String,
    var name: String,
    var location: Map<String,Double> = mapOf("latitude" to 0.0, "longitude" to 0.0),
    var Time: Long = System.currentTimeMillis(),
    var IsActive: Boolean = false,
    var fcmToken: String? = null
)
