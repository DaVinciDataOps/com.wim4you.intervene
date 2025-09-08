package com.wim4you.intervene

import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData

object AppState {
    var isPatrolling: Boolean = false
    var isGuidedTrip: Boolean = false
    var isDistressState: Boolean = false

    var vigilante: VigilanteData? = null
    var person: PersonData? = null
    var DistressRadius: Double = 2.0 //km
}
