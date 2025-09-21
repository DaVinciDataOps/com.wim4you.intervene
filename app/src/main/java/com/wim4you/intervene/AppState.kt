package com.wim4you.intervene

import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData

object AppState {
    var isPatrolling: Boolean = false
    var isGuidedTrip: Boolean = false
    var isDistressState: Boolean = false
    var selectedDistressCall: Int = -1
    var vigilante: VigilanteData? = null

    var snackBarMessage:String = ""
}
