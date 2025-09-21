package com.wim4you.intervene.fbdata

import android.os.Parcelable
import com.google.firebase.Timestamp
import kotlinx.parcelize.Parcelize

@Parcelize
data class InterveningData(
    val id: String,
    val timestamp: Timestamp,
    val personId: String,
    val vigilanteId: String,
):Parcelable{}
