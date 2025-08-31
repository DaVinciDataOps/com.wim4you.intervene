package com.wim4you.intervene

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseUtils {

    fun onConnect(context: Context, callback:(FirebaseFirestore) -> Unit){
        FirebaseApp.initializeApp(context)
        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()
        callback(db)
    }

    fun getVigilantes(context: Context, db:FirebaseFirestore, radius: Double){

    }
}