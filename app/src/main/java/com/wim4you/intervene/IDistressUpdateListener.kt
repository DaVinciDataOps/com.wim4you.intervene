package com.wim4you.intervene

import com.wim4you.intervene.data.DistressCallData

fun interface IDistressUpdateListener {
    fun onDistressCallsUpdated(distressCalls: List<DistressCallData>)
}