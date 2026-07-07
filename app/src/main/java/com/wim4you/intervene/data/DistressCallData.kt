package com.wim4you.intervene.data

data class DistressCallData(
    val id: String?,
    val alias: String?,
    val address: String?,
    val startTime: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
