package com.wim4you.intervene.fbdata

import com.google.firebase.Timestamp

data class InterveningData(
    val id: String,
    val timestamp: Timestamp,
    val personId: String,
    val vigilanteId: String,
)
