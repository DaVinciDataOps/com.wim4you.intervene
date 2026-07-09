package com.wim4you.intervene

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * Single entry point for Realtime Database access (Europe region instance from google-services.json).
 */
object FirebaseDatabaseProvider {

    private const val DATABASE_URL =
        "https://com-wim4you-intervene-default-rtdb.europe-west1.firebasedatabase.app"

    fun reference(): DatabaseReference = FirebaseDatabase.getInstance(DATABASE_URL).reference
}
