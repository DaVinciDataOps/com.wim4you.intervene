package com.wim4you.intervene.mappings

import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.wim4you.intervene.data.AddressData
import com.wim4you.intervene.data.PersonData

object DataMappings {
    fun toDistressDataMap(
        personData: PersonData,
        geoLocation: GeoLocation,
        address: AddressData,
        init: Boolean = false
    ): MutableMap<String, Any?> {
        val distressDataMap = mutableMapOf<String, Any?>(
            "l" to listOf(geoLocation.latitude, geoLocation.longitude),
            "g" to GeoFireUtils.getGeoHashForLocation(geoLocation),
            "alias" to personData.alias,
            "personId" to personData.id,
            "time" to System.currentTimeMillis(),
            "active" to true,
            "fcmToken" to null,
            "address" to address.street,
            "city" to address.city,
            "country" to address.country
        )

        if (init) {
            distressDataMap["startTime"] = System.currentTimeMillis()
        }

        return distressDataMap
    }
}
