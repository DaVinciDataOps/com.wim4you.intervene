package com.wim4you.intervene.distressstream.webrtc

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.FirebaseDatabaseProvider
import com.wim4you.intervene.distressstream.DistressStreamConstants
import com.wim4you.intervene.SecureLog
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

object WebRtcSignaling {
    private const val TAG = "WebRtcSignaling"

    const val FIELD_TYPE = "type"
    const val FIELD_SDP = "sdp"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_CANDIDATE = "candidate"
    const val FIELD_SDP_MID = "sdpMid"
    const val FIELD_SDP_MLINE_INDEX = "sdpMLineIndex"

    const val NODE_OFFER = "offer"
    const val NODE_ANSWER = "answer"
    const val NODE_VIEWER_ICE = "viewer_ice"
    const val NODE_PUBLISHER_ICE = "publisher_ice"

    fun viewersRef(distressUid: String): DatabaseReference {
        return FirebaseDatabaseProvider.reference()
            .child(DistressStreamConstants.RTDB_ROOT)
            .child(distressUid)
            .child("viewers")
    }

    fun viewerRef(distressUid: String, patrolUid: String): DatabaseReference {
        return viewersRef(distressUid).child(patrolUid)
    }

    fun sessionDescriptionToMap(description: SessionDescription): Map<String, Any> {
        return mapOf(
            FIELD_TYPE to description.type.canonicalForm(),
            FIELD_SDP to description.description,
            FIELD_CREATED_AT to System.currentTimeMillis(),
        )
    }

    fun sessionDescriptionFromSnapshot(snapshot: DataSnapshot): SessionDescription? {
        val type = snapshot.child(FIELD_TYPE).getValue(String::class.java) ?: return null
        val sdp = snapshot.child(FIELD_SDP).getValue(String::class.java) ?: return null
        return SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
    }

    fun iceCandidateToMap(candidate: IceCandidate): Map<String, Any?> {
        return mapOf(
            FIELD_CANDIDATE to candidate.sdp,
            FIELD_SDP_MID to candidate.sdpMid,
            FIELD_SDP_MLINE_INDEX to candidate.sdpMLineIndex,
        )
    }

    fun iceCandidateFromSnapshot(snapshot: DataSnapshot): IceCandidate? {
        val candidate = snapshot.child(FIELD_CANDIDATE).getValue(String::class.java) ?: return null
        val sdpMid = snapshot.child(FIELD_SDP_MID).getValue(String::class.java)
        val sdpMLineIndex = snapshot.child(FIELD_SDP_MLINE_INDEX).getValue(Int::class.java) ?: return null
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    fun observeOffers(
        distressUid: String,
        onOffer: (patrolUid: String, offer: SessionDescription) -> Unit,
    ): ChildEventListener {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleOfferSnapshot(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleOfferSnapshot(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                SecureLog.e(TAG, "Offer listener cancelled for $distressUid", error.toException())
            }

            private fun handleOfferSnapshot(viewerSnapshot: DataSnapshot) {
                val patrolUid = viewerSnapshot.key ?: return
                val offerSnapshot = viewerSnapshot.child(NODE_OFFER)
                val offer = sessionDescriptionFromSnapshot(offerSnapshot) ?: return
                onOffer(patrolUid, offer)
            }
        }
        viewersRef(distressUid).addChildEventListener(listener)
        return listener
    }

    fun observeAnswer(
        distressUid: String,
        patrolUid: String,
        onAnswer: (SessionDescription) -> Unit,
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val answer = sessionDescriptionFromSnapshot(snapshot) ?: return
                onAnswer(answer)
            }

            override fun onCancelled(error: DatabaseError) {
                SecureLog.e(TAG, "Answer listener cancelled for $distressUid/$patrolUid", error.toException())
            }
        }
        viewerRef(distressUid, patrolUid).child(NODE_ANSWER).addValueEventListener(listener)
        return listener
    }

    fun observeRemoteIceCandidates(
        distressUid: String,
        patrolUid: String,
        node: String,
        onCandidate: (IceCandidate) -> Unit,
    ): ChildEventListener {
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidate = iceCandidateFromSnapshot(snapshot) ?: return
                onCandidate(candidate)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val candidate = iceCandidateFromSnapshot(snapshot) ?: return
                onCandidate(candidate)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                SecureLog.e(TAG, "ICE listener cancelled for $distressUid/$patrolUid/$node", error.toException())
            }
        }
        viewerRef(distressUid, patrolUid).child(node).addChildEventListener(listener)
        return listener
    }

    fun sendOffer(distressUid: String, patrolUid: String, offer: SessionDescription) {
        viewerRef(distressUid, patrolUid)
            .child(NODE_OFFER)
            .setValue(sessionDescriptionToMap(offer))
    }

    fun sendAnswer(distressUid: String, patrolUid: String, answer: SessionDescription) {
        viewerRef(distressUid, patrolUid)
            .child(NODE_ANSWER)
            .setValue(sessionDescriptionToMap(answer))
    }

    fun sendIceCandidate(
        distressUid: String,
        patrolUid: String,
        node: String,
        candidate: IceCandidate,
    ) {
        val candidateId = "ice_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        viewerRef(distressUid, patrolUid)
            .child(node)
            .child(candidateId)
            .setValue(iceCandidateToMap(candidate))
    }

    fun clearViewerSignaling(distressUid: String, patrolUid: String) {
        viewerRef(distressUid, patrolUid).child(NODE_OFFER).removeValue()
        viewerRef(distressUid, patrolUid).child(NODE_ANSWER).removeValue()
        viewerRef(distressUid, patrolUid).child(NODE_VIEWER_ICE).removeValue()
        viewerRef(distressUid, patrolUid).child(NODE_PUBLISHER_ICE).removeValue()
    }

    fun clearAllSignaling(distressUid: String) {
        viewersRef(distressUid).get().addOnCompleteListener { task ->
            if (!task.isSuccessful) return@addOnCompleteListener
            task.result?.children?.forEach { child ->
                val patrolUid = child.key ?: return@forEach
                clearViewerSignaling(distressUid, patrolUid)
            }
        }
    }
}
