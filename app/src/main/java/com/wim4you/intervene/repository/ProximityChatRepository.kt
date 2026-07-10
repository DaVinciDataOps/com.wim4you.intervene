package com.wim4you.intervene.repository

import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.firebase.geofire.GeoQuery
import com.firebase.geofire.GeoQueryDataEventListener
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wim4you.intervene.FirebaseUtils
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.fbdata.ChatMessageData
import com.wim4you.intervene.fbdata.ChatParticipantData
import com.wim4you.intervene.fbdata.ChatRoomData
import com.wim4you.intervene.helpers.DistanceUtils
import com.wim4you.intervene.proximitychat.ChatMessageItem
import com.wim4you.intervene.proximitychat.ChatRoomStatus
import com.wim4you.intervene.proximitychat.ChatRoomSummary
import com.wim4you.intervene.proximitychat.NearbyChatUser
import com.wim4you.intervene.proximitychat.ProximityChatConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class ProximityChatRepository @Inject constructor() {

    private val database = FirebaseDatabase.getInstance().reference
    private val geoFire = GeoFire(database.child(ProximityChatConstants.PRESENCE_PATH))

    fun directRoomId(uid1: String, uid2: String): String {
        val sorted = listOf(uid1, uid2).sorted()
        return "direct_${sorted[0]}_${sorted[1]}"
    }

    fun otherUidFromDirectRoom(roomId: String, myUid: String): String? {
        if (!roomId.startsWith("direct_")) return null
        val parts = roomId.removePrefix("direct_").split("_")
        if (parts.size != 2) return null
        return when (myUid) {
            parts[0] -> parts[1]
            parts[1] -> parts[0]
            else -> null
        }
    }

    suspend fun updatePresence(uid: String, alias: String, latitude: Double, longitude: Double) {
        val geoHash = GeoFireUtils.getGeoHashForLocation(GeoLocation(latitude, longitude))
        val participant = ChatParticipantData(
            alias = alias,
            g = geoHash,
            l = listOf(latitude, longitude),
            time = System.currentTimeMillis(),
            isActive = true,
        )
        database.child(ProximityChatConstants.PRESENCE_PATH)
            .child(uid)
            .setValueOnce(participant)
    }

    suspend fun clearPresence(uid: String) {
        database.child(ProximityChatConstants.PRESENCE_PATH)
            .child(uid)
            .child("active")
            .setValueOnce(false)
    }

    fun observeNearbyUsers(
        latitude: Double,
        longitude: Double,
        myUid: String,
    ): Flow<List<NearbyChatUser>> = callbackFlow {
        val participants = linkedMapOf<String, NearbyChatUser>()
        val geoQuery: GeoQuery = geoFire.queryAtLocation(
            GeoLocation(latitude, longitude),
            ProximityChatConstants.PROXIMITY_RADIUS_KM,
        )

        val listener = object : GeoQueryDataEventListener {
            override fun onDataEntered(dataSnapshot: DataSnapshot, location: GeoLocation) {
                val uid = dataSnapshot.key ?: return
                if (uid == myUid) return
                val participant = dataSnapshot.getValue(ChatParticipantData::class.java) ?: return
                if (participant.isActive != true) return
                if (!isFresh(participant.time)) return
                val alias = participant.alias?.takeIf { it.isNotBlank() } ?: return
                val distance = DistanceUtils.metersBetween(
                    latitude,
                    longitude,
                    location.latitude,
                    location.longitude,
                )
                participants[uid] = NearbyChatUser(
                    uid = uid,
                    alias = alias,
                    distanceMeters = distance,
                    latitude = location.latitude,
                    longitude = location.longitude,
                )
                trySend(participants.values.sortedBy { it.distanceMeters ?: Double.MAX_VALUE })
            }

            override fun onDataExited(dataSnapshot: DataSnapshot) {
                val uid = dataSnapshot.key ?: return
                if (participants.remove(uid) != null) {
                    trySend(participants.values.sortedBy { it.distanceMeters ?: Double.MAX_VALUE })
                }
            }

            override fun onDataMoved(dataSnapshot: DataSnapshot, location: GeoLocation) {
                onDataEntered(dataSnapshot, location)
            }

            override fun onDataChanged(dataSnapshot: DataSnapshot, location: GeoLocation) {
                val uid = dataSnapshot.key ?: return
                val participant = dataSnapshot.getValue(ChatParticipantData::class.java)
                if (participant?.isActive != true || !isFresh(participant.time)) {
                    if (participants.remove(uid) != null) {
                        trySend(participants.values.sortedBy { it.distanceMeters ?: Double.MAX_VALUE })
                    }
                    return
                }
                onDataEntered(dataSnapshot, location)
            }

            override fun onGeoQueryReady() = Unit

            override fun onGeoQueryError(error: DatabaseError) {
                SecureLog.e(TAG, "Nearby chat geo query error: ${error.message}")
            }
        }

        geoQuery.addGeoQueryDataEventListener(listener)
        awaitClose { geoQuery.removeAllListeners() }
    }

    fun observeMyRooms(myUid: String): Flow<List<ChatRoomSummary>> = callbackFlow {
        val roomSummaries = linkedMapOf<String, ChatRoomSummary>()
        val roomListeners = linkedMapOf<String, ValueEventListener>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun emitSorted() {
            trySend(roomSummaries.values.sortedByDescending { it.lastMessageAt })
        }

        suspend fun loadRoom(roomId: String) {
            val roomSnapshot = database.child(ProximityChatConstants.ROOMS_PATH)
                .child(roomId)
                .getOnce()
            if (!roomSnapshot.exists()) {
                roomSummaries.remove(roomId)
                database.child(ProximityChatConstants.USER_ROOMS_PATH)
                    .child(myUid)
                    .child(roomId)
                    .removeValueOnce()
                return
            }
            val room = roomSnapshot.getValue(ChatRoomData::class.java) ?: run {
                roomSummaries.remove(roomId)
                return
            }
            val participantsSnapshot = roomSnapshot.child("participants")
            val participantAliases = participantsSnapshot.children.mapNotNull { child ->
                child.key to child.child("alias").getValue(String::class.java)
            }
            val displayName = when (room.type) {
                ProximityChatConstants.ROOM_TYPE_GROUP -> {
                    room.name?.takeIf { it.isNotBlank() }
                        ?: participantAliases
                            .filter { it.first != myUid }
                            .joinToString(", ") { it.second.orEmpty() }
                            .ifBlank { roomId }
                }
                else -> {
                    val otherAlias = participantAliases
                        .firstOrNull { it.first != myUid }
                        ?.second
                        ?.takeIf { it.isNotBlank() }
                    if (otherAlias == null) {
                        roomSummaries.remove(roomId)
                        database.child(ProximityChatConstants.USER_ROOMS_PATH)
                            .child(myUid)
                            .child(roomId)
                            .removeValueOnce()
                        return
                    }
                    otherAlias
                }
            }
            val status = room.status?.takeIf { it.isNotBlank() }
                ?: ProximityChatConstants.ROOM_STATUS_ACTIVE
            val initiatorUid = room.initiatorUid
            val isIncomingRing = status == ProximityChatConstants.ROOM_STATUS_RINGING &&
                initiatorUid != null &&
                initiatorUid != myUid
            val initiatorAlias = if (isIncomingRing) {
                participantsSnapshot.child(initiatorUid!!)
                    .child("alias")
                    .getValue(String::class.java)
            } else {
                null
            }
            val lastMessageAt = room.lastMessageAt ?: room.createdAt ?: 0L
            val lastMessageSenderId = room.lastMessageSenderId
                ?: roomSnapshot.child("lastMessageSenderId").getValue(String::class.java)
            val myLastReadAt = participantsSnapshot.child(myUid)
                .child("lastReadAt")
                .getValue(Long::class.java) ?: 0L
            val otherLastReadAts = participantsSnapshot.children
                .filter { it.key != myUid }
                .map { it.child("lastReadAt").getValue(Long::class.java) ?: 0L }
            val isNotifiable = ProximityChatConstants.isNotifiableChatStatus(status)
            val hasUnreadForMe = isNotifiable && lastMessageAt > myLastReadAt && (
                isIncomingRing ||
                    (lastMessageSenderId != null && lastMessageSenderId != myUid)
                )
            val hasUnreadByOthers = isNotifiable && otherLastReadAts.any { it < lastMessageAt }
            roomSummaries[roomId] = ChatRoomSummary(
                roomId = roomId,
                displayName = displayName,
                isGroup = room.type == ProximityChatConstants.ROOM_TYPE_GROUP,
                participantCount = participantAliases.size,
                lastMessageAt = lastMessageAt,
                status = status,
                isIncomingRing = isIncomingRing,
                initiatorAlias = initiatorAlias,
                hasUnreadForMe = hasUnreadForMe,
                hasUnreadByOthers = hasUnreadByOthers,
                hasUnreadIndicator = hasUnreadForMe || hasUnreadByOthers,
            )
        }

        fun attachRoomListener(roomId: String) {
            if (roomId in roomListeners) return
            val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    scope.launch {
                        try {
                            loadRoom(roomId)
                            emitSorted()
                        } catch (exception: Exception) {
                            SecureLog.e(TAG, "Failed to reload room $roomId", exception)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    SecureLog.e(TAG, "Room listener cancelled for $roomId: ${error.message}")
                }
            }
            roomRef.addValueEventListener(listener)
            roomListeners[roomId] = listener
        }

        fun detachRoomListener(roomId: String) {
            val listener = roomListeners.remove(roomId) ?: return
            database.child(ProximityChatConstants.ROOMS_PATH)
                .child(roomId)
                .removeEventListener(listener)
        }

        val userRoomsRef = database.child(ProximityChatConstants.USER_ROOMS_PATH).child(myUid)
        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                scope.launch {
                    val roomIds = snapshot.children.mapNotNull { it.key }.toSet()
                    roomSummaries.keys.filter { it !in roomIds }.forEach { roomId ->
                        roomSummaries.remove(roomId)
                        detachRoomListener(roomId)
                    }
                    roomIds.forEach { roomId ->
                        try {
                            loadRoom(roomId)
                            attachRoomListener(roomId)
                        } catch (exception: Exception) {
                            SecureLog.e(TAG, "Failed to load room $roomId", exception)
                        }
                    }
                    emitSorted()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                SecureLog.e(TAG, "User rooms value listener cancelled: ${error.message}")
            }
        }

        userRoomsRef.addValueEventListener(valueListener)
        awaitClose {
            scope.cancel()
            userRoomsRef.removeEventListener(valueListener)
            roomListeners.keys.toList().forEach { detachRoomListener(it) }
        }
    }

    /**
     * Real-time listener for incoming unread messages from direct-chat partners.
     * Emits the UIDs of nearby chat partners who have sent messages the current user has not read.
     */
    fun observeIncomingUnreadSenderUids(myUid: String): Flow<Set<String>> = callbackFlow {
        data class DirectRoomUnreadState(
            val otherUid: String,
            var myLastReadAt: Long = 0L,
            var otherLastReadAt: Long = 0L,
            var latestMessageAt: Long = 0L,
            var latestMessageSenderId: String? = null,
            var status: String = ProximityChatConstants.ROOM_STATUS_ACTIVE,
            var initiatorUid: String? = null,
            var myAccepted: Boolean = true,
            var roomActivityAt: Long = 0L,
        )

        val roomStates = linkedMapOf<String, DirectRoomUnreadState>()
        val unreadSenderUids = linkedSetOf<String>()
        val roomListeners = linkedMapOf<String, ValueEventListener>()
        val messageListeners = linkedMapOf<String, ChildEventListener>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun emitUnreadSenders() {
            trySend(unreadSenderUids.toSet())
        }

        fun recomputeUnreadForRoom(roomId: String) {
            val state = roomStates[roomId] ?: return
            if (state.status == ProximityChatConstants.ROOM_STATUS_DECLINED) {
                unreadSenderUids.remove(state.otherUid)
                emitUnreadSenders()
                return
            }
            val activityAt = maxOf(state.latestMessageAt, state.roomActivityAt)
            val hasUnread = when {
                state.latestMessageSenderId != null &&
                    state.latestMessageSenderId != myUid &&
                    state.latestMessageAt > state.myLastReadAt -> true
                state.status == ProximityChatConstants.ROOM_STATUS_RINGING &&
                    state.initiatorUid != null &&
                    state.initiatorUid != myUid &&
                    !state.myAccepted &&
                    activityAt > state.myLastReadAt -> true
                state.status == ProximityChatConstants.ROOM_STATUS_RINGING &&
                    state.initiatorUid == myUid &&
                    state.otherLastReadAt < activityAt -> true
                ProximityChatConstants.isNotifiableChatStatus(state.status) &&
                    state.latestMessageSenderId == myUid &&
                    state.latestMessageAt > state.otherLastReadAt -> true
                else -> false
            }
            if (hasUnread) {
                unreadSenderUids.add(state.otherUid)
            } else {
                unreadSenderUids.remove(state.otherUid)
            }
            emitUnreadSenders()
        }

        fun handleMessageSnapshot(roomId: String, snapshot: DataSnapshot) {
            val state = roomStates[roomId] ?: return
            val data = snapshot.getValue(ChatMessageData::class.java) ?: return
            val senderId = data.senderId ?: return
            val timestamp = data.timestamp ?: return
            if (timestamp >= state.latestMessageAt) {
                state.latestMessageAt = timestamp
                state.latestMessageSenderId = senderId
                recomputeUnreadForRoom(roomId)
            }
        }

        fun attachMessageListener(roomId: String) {
            if (roomId in messageListeners) return
            val messagesRef = database.child(ProximityChatConstants.MESSAGES_PATH).child(roomId)
            val listener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    handleMessageSnapshot(roomId, snapshot)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    handleMessageSnapshot(roomId, snapshot)
                }

                override fun onChildRemoved(snapshot: DataSnapshot) = Unit

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

                override fun onCancelled(error: DatabaseError) {
                    SecureLog.e(TAG, "Nearby unread message listener cancelled: ${error.message}")
                }
            }
            messagesRef.addChildEventListener(listener)
            messageListeners[roomId] = listener
        }

        fun detachMessageListener(roomId: String) {
            val listener = messageListeners.remove(roomId) ?: return
            database.child(ProximityChatConstants.MESSAGES_PATH)
                .child(roomId)
                .removeEventListener(listener)
        }

        fun attachRoomListener(roomId: String) {
            if (roomId in roomListeners) return
            val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val state = roomStates[roomId] ?: return
                    val room = snapshot.getValue(ChatRoomData::class.java)
                    state.myLastReadAt = snapshot.child("participants")
                        .child(myUid)
                        .child("lastReadAt")
                        .getValue(Long::class.java) ?: state.myLastReadAt
                    state.otherLastReadAt = snapshot.child("participants")
                        .child(state.otherUid)
                        .child("lastReadAt")
                        .getValue(Long::class.java) ?: state.otherLastReadAt
                    state.myAccepted = snapshot.child("participants")
                        .child(myUid)
                        .child("accepted")
                        .getValue(Boolean::class.java) == true
                    state.initiatorUid = room?.initiatorUid
                    state.roomActivityAt = room?.lastMessageAt ?: room?.createdAt ?: state.roomActivityAt
                    room?.lastMessageSenderId?.let { senderId ->
                        state.latestMessageSenderId = senderId
                        state.latestMessageAt = maxOf(state.latestMessageAt, state.roomActivityAt)
                    }
                    state.status = room?.status?.takeIf { it.isNotBlank() }
                        ?: ProximityChatConstants.ROOM_STATUS_ACTIVE
                    recomputeUnreadForRoom(roomId)
                }

                override fun onCancelled(error: DatabaseError) {
                    SecureLog.e(TAG, "Nearby unread room listener cancelled: ${error.message}")
                }
            }
            roomRef.addValueEventListener(listener)
            roomListeners[roomId] = listener
        }

        fun detachRoomListener(roomId: String) {
            val listener = roomListeners.remove(roomId) ?: return
            database.child(ProximityChatConstants.ROOMS_PATH)
                .child(roomId)
                .removeEventListener(listener)
        }

        suspend fun attachDirectRoom(roomId: String) {
            val otherUid = otherUidFromDirectRoom(roomId, myUid) ?: return
            val roomSnapshot = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId).getOnce()
            val room = roomSnapshot.getValue(ChatRoomData::class.java) ?: return
            val myLastReadAt = roomSnapshot.child("participants")
                .child(myUid)
                .child("lastReadAt")
                .getValue(Long::class.java) ?: 0L
            val otherLastReadAt = roomSnapshot.child("participants")
                .child(otherUid)
                .child("lastReadAt")
                .getValue(Long::class.java) ?: 0L
            val myAccepted = roomSnapshot.child("participants")
                .child(myUid)
                .child("accepted")
                .getValue(Boolean::class.java) == true
            val activityAt = room.lastMessageAt ?: room.createdAt ?: 0L
            roomStates[roomId] = DirectRoomUnreadState(
                otherUid = otherUid,
                myLastReadAt = myLastReadAt,
                otherLastReadAt = otherLastReadAt,
                myAccepted = myAccepted,
                initiatorUid = room.initiatorUid,
                roomActivityAt = activityAt,
                status = room.status?.takeIf { it.isNotBlank() }
                    ?: ProximityChatConstants.ROOM_STATUS_ACTIVE,
            )
            attachRoomListener(roomId)
            attachMessageListener(roomId)
            recomputeUnreadForRoom(roomId)
        }

        fun detachDirectRoom(roomId: String) {
            val state = roomStates.remove(roomId) ?: return
            unreadSenderUids.remove(state.otherUid)
            detachRoomListener(roomId)
            detachMessageListener(roomId)
            emitUnreadSenders()
        }

        val userRoomsRef = database.child(ProximityChatConstants.USER_ROOMS_PATH).child(myUid)
        val userRoomsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                scope.launch {
                    val roomIds = snapshot.children.mapNotNull { it.key }.toSet()
                    roomStates.keys.filter { it !in roomIds }.forEach { detachDirectRoom(it) }
                    roomIds.forEach { roomId ->
                        if (roomId !in roomStates) {
                            try {
                                attachDirectRoom(roomId)
                            } catch (exception: Exception) {
                                SecureLog.e(TAG, "Failed to attach nearby unread listener for $roomId", exception)
                            }
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                SecureLog.e(TAG, "Nearby unread user rooms listener cancelled: ${error.message}")
            }
        }

        userRoomsRef.addValueEventListener(userRoomsListener)
        awaitClose {
            scope.cancel()
            userRoomsRef.removeEventListener(userRoomsListener)
            roomStates.keys.toList().forEach { detachDirectRoom(it) }
        }
    }

    suspend fun ensureDirectRoom(
        myUid: String,
        otherUid: String,
        myAlias: String,
        otherAlias: String,
    ): String {
        val roomId = directRoomId(myUid, otherUid)
        val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId)
        val existing = roomRef.getOnce()
        if (!existing.exists()) {
            createDirectRoom(roomRef, roomId, myUid, otherUid, myAlias, otherAlias)
        } else {
            val myRoomLink = database.child(ProximityChatConstants.USER_ROOMS_PATH)
                .child(myUid)
                .child(roomId)
                .getOnce()
            val participantsSnapshot = existing.child("participants")
            val hasParticipants = participantsSnapshot.hasChild(myUid) &&
                participantsSnapshot.hasChild(otherUid)
            if (!myRoomLink.exists() || !hasParticipants) {
                resetDirectRoom(roomRef, roomId, myUid, otherUid, myAlias, otherAlias)
            } else {
                linkUserToRoom(myUid, roomId, System.currentTimeMillis())
                roomRef.child("participants").child(myUid).child("alias").setValueOnce(myAlias)
                roomRef.child("participants").child(otherUid).child("alias").setValueOnce(otherAlias)
            }
        }
        return roomId
    }

    private suspend fun createDirectRoom(
        roomRef: DatabaseReference,
        roomId: String,
        myUid: String,
        otherUid: String,
        myAlias: String,
        otherAlias: String,
    ) {
        val now = System.currentTimeMillis()
        writeDirectRoomMetadata(roomRef, myUid, now)
        linkUserToRoom(myUid, roomId, now)
        linkUserToRoom(otherUid, roomId, now)
        writeDirectRoomParticipants(roomRef, myUid, otherUid, myAlias, otherAlias, now)
    }

    private suspend fun resetDirectRoom(
        roomRef: DatabaseReference,
        roomId: String,
        myUid: String,
        otherUid: String,
        myAlias: String,
        otherAlias: String,
    ) {
        clearRoomMessagesBestEffort(roomId)
        val now = System.currentTimeMillis()
        writeDirectRoomMetadata(roomRef, myUid, now)
        writeDirectRoomParticipants(roomRef, myUid, otherUid, myAlias, otherAlias, now)
        linkUserToRoom(myUid, roomId, now)
        linkUserToRoom(otherUid, roomId, now)
    }

    private suspend fun writeDirectRoomMetadata(
        roomRef: DatabaseReference,
        initiatorUid: String,
        now: Long,
    ) {
        roomRef.child("type").setValueOnce(ProximityChatConstants.ROOM_TYPE_DIRECT)
        roomRef.child("createdAt").setValueOnce(now)
        roomRef.child("lastMessageAt").setValueOnce(now)
        roomRef.child("status").setValueOnce(ProximityChatConstants.ROOM_STATUS_RINGING)
        roomRef.child("initiatorUid").setValueOnce(initiatorUid)
        roomRef.child("lastMessageSenderId").removeValueOnce()
    }

    private suspend fun writeDirectRoomParticipants(
        roomRef: DatabaseReference,
        myUid: String,
        otherUid: String,
        myAlias: String,
        otherAlias: String,
        now: Long,
    ) {
        roomRef.child("participants").child(myUid).setValueOnce(
            mapOf("alias" to myAlias, "joinedAt" to now, "accepted" to true),
        )
        roomRef.child("participants").child(otherUid).setValueOnce(
            mapOf("alias" to otherAlias, "joinedAt" to now, "accepted" to false),
        )
    }

    suspend fun removeChatRoom(roomId: String, myUid: String) {
        val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId)
        val snapshot = roomRef.getOnce()
        val isGroup = snapshot.child("type").getValue(String::class.java) ==
            ProximityChatConstants.ROOM_TYPE_GROUP

        database.child(ProximityChatConstants.USER_ROOMS_PATH)
            .child(myUid)
            .child(roomId)
            .removeValueOnce()

        if (isGroup) {
            if (snapshot.exists()) {
                roomRef.child("participants").child(myUid).removeValueOnce()
            }
            return
        }

        clearRoomMessagesBestEffort(roomId)

        snapshot.child("participants").children.forEach { child ->
            val uid = child.key ?: return@forEach
            if (uid == myUid) return@forEach
            try {
                database.child(ProximityChatConstants.USER_ROOMS_PATH)
                    .child(uid)
                    .child(roomId)
                    .removeValueOnce()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Could not unlink room $roomId from user $uid", exception)
            }
        }

        if (snapshot.exists()) {
            try {
                roomRef.removeValueOnce()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Could not delete room $roomId", exception)
            }
        }
    }

    private suspend fun clearRoomMessagesBestEffort(roomId: String): Boolean {
        val messagesRef = database.child(ProximityChatConstants.MESSAGES_PATH).child(roomId)
        val snapshot = messagesRef.getOnce()
        if (!snapshot.exists()) return true

        try {
            messagesRef.removeValueOnce()
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Bulk delete failed for $roomId", exception)
        }

        val afterBulkDelete = messagesRef.getOnce()
        if (!afterBulkDelete.exists()) return true

        deleteAllMessages(roomId)

        val remaining = messagesRef.getOnce()
        if (remaining.hasChildren()) {
            SecureLog.e(
                TAG,
                "Could not delete ${remaining.childrenCount} messages from Firebase for room $roomId",
            )
            return false
        }
        return true
    }

    private suspend fun deleteAllMessages(roomId: String) {
        val messagesRef = database.child(ProximityChatConstants.MESSAGES_PATH).child(roomId)
        val snapshot = messagesRef.getOnce()
        if (!snapshot.exists()) return

        val updates = mutableMapOf<String, Any?>()
        snapshot.children.forEach { child ->
            val messageId = child.key ?: return@forEach
            updates["/${ProximityChatConstants.MESSAGES_PATH}/$roomId/$messageId"] = null
        }
        if (updates.isEmpty()) return

        try {
            database.updateChildrenOnce(updates)
        } catch (exception: Exception) {
            SecureLog.e(TAG, "Batch delete failed for $roomId", exception)
            snapshot.children.forEach { child ->
                try {
                    child.ref.removeValueOnce()
                } catch (deleteException: Exception) {
                    SecureLog.e(
                        TAG,
                        "Could not delete message ${child.key} in $roomId",
                        deleteException,
                    )
                }
            }
        }
    }

    suspend fun getMyRoomIds(myUid: String): List<String> {
        val snapshot = database.child(ProximityChatConstants.USER_ROOMS_PATH)
            .child(myUid)
            .getOnce()
        return snapshot.children.mapNotNull { it.key }
    }

    suspend fun clearAllChatRooms(myUid: String) {
        val roomIds = getMyRoomIds(myUid)
        roomIds.forEach { roomId ->
            removeChatRoom(roomId, myUid)
        }
    }

    suspend fun isRoomActive(roomId: String): Boolean {
        val snapshot = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId).getOnce()
        val status = snapshot.child("status").getValue(String::class.java)
        return status.isNullOrBlank() || status == ProximityChatConstants.ROOM_STATUS_ACTIVE
    }

    suspend fun isRoomMessagingAllowed(roomId: String): Boolean {
        val snapshot = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId).getOnce()
        val status = snapshot.child("status").getValue(String::class.java)
            ?: ProximityChatConstants.ROOM_STATUS_ACTIVE
        return ProximityChatConstants.isNotifiableChatStatus(status)
    }

    fun observeRoomStatus(roomId: String, myUid: String): Flow<ChatRoomStatus> = callbackFlow {
        val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(ChatRoomData::class.java)
                val status = room?.status?.takeIf { it.isNotBlank() }
                    ?: ProximityChatConstants.ROOM_STATUS_ACTIVE
                val initiatorUid = room?.initiatorUid
                val isGroup = room?.type == ProximityChatConstants.ROOM_TYPE_GROUP
                var myAccepted = true
                var acceptedCount = 0
                snapshot.child("participants").children.forEach { child ->
                    val accepted = child.child("accepted").getValue(Boolean::class.java) == true
                    if (accepted) acceptedCount++
                    if (child.key == myUid) myAccepted = accepted
                }
                trySend(
                    ChatRoomStatus(
                        status = status,
                        initiatorUid = initiatorUid,
                        myAccepted = myAccepted,
                        acceptedCount = acceptedCount,
                        isGroup = isGroup,
                    ),
                )
            }

            override fun onCancelled(error: DatabaseError) {
                SecureLog.e(TAG, "Room status listener cancelled: ${error.message}")
            }
        }
        roomRef.addValueEventListener(listener)
        awaitClose { roomRef.removeEventListener(listener) }
    }

    suspend fun acceptChatInvite(roomId: String, myUid: String) {
        val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId)
        val snapshot = roomRef.getOnce()
        val initiatorUid = snapshot.child("initiatorUid").getValue(String::class.java)
        val isGroup = snapshot.child("type").getValue(String::class.java) ==
            ProximityChatConstants.ROOM_TYPE_GROUP
        roomRef.child("participants").child(myUid).child("accepted").setValueOnce(true)
        var acceptedCount = 0
        snapshot.child("participants").children.forEach { child ->
            val accepted = if (child.key == myUid) {
                true
            } else {
                child.child("accepted").getValue(Boolean::class.java) == true
            }
            if (accepted) acceptedCount++
        }
        val shouldActivate = if (isGroup) {
            acceptedCount >= 2
        } else {
            myUid != initiatorUid
        }
        if (shouldActivate) {
            roomRef.child("status").setValueOnce(ProximityChatConstants.ROOM_STATUS_ACTIVE)
        }
    }

    suspend fun declineChatInvite(roomId: String, myUid: String) {
        val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId)
        database.child(ProximityChatConstants.USER_ROOMS_PATH)
            .child(myUid)
            .child(roomId)
            .removeValueOnce()
        val snapshot = roomRef.getOnce()
        val isGroup = snapshot.child("type").getValue(String::class.java) ==
            ProximityChatConstants.ROOM_TYPE_GROUP
        if (isGroup) {
            roomRef.child("participants").child(myUid).removeValueOnce()
        } else {
            roomRef.child("status").setValueOnce(ProximityChatConstants.ROOM_STATUS_DECLINED)
        }
    }

    suspend fun createGroupRoom(
        myUid: String,
        participantUids: Set<String>,
        aliases: Map<String, String>,
        groupName: String,
    ): String {
        val allParticipants = (participantUids + myUid).toSet()
        val roomRef = database.child(ProximityChatConstants.ROOMS_PATH).push()
        val roomId = roomRef.key ?: throw IllegalStateException("Failed to create group room id")
        val now = System.currentTimeMillis()
        val room = ChatRoomData(
            type = ProximityChatConstants.ROOM_TYPE_GROUP,
            name = groupName,
            createdAt = now,
            lastMessageAt = now,
            status = ProximityChatConstants.ROOM_STATUS_RINGING,
            initiatorUid = myUid,
        )
        roomRef.setValueOnce(room)
        allParticipants.forEach { uid ->
            linkUserToRoom(uid, roomId, now)
            val alias = aliases[uid] ?: "User"
            val accepted = uid == myUid
            roomRef.child("participants").child(uid).setValueOnce(
                mapOf("alias" to alias, "joinedAt" to now, "accepted" to accepted),
            )
        }
        return roomId
    }

    fun observeMessages(roomId: String, myUid: String): Flow<List<ChatMessageItem>> = callbackFlow {
        val messages = linkedMapOf<String, ChatMessageItem>()
        val messagesRef = database.child(ProximityChatConstants.MESSAGES_PATH).child(roomId)

        val listener = object : ChildEventListener {
            private fun handleSnapshot(snapshot: DataSnapshot) {
                val messageId = snapshot.key ?: return
                val data = snapshot.getValue(ChatMessageData::class.java) ?: return
                val senderId = data.senderId ?: return
                val timestamp = data.timestamp ?: return
                val isDeleted = data.deleted == true ||
                    data.text == ProximityChatConstants.REMOVED_MESSAGE_TEXT
                val text = if (isDeleted) {
                    ""
                } else {
                    data.text?.takeIf { it.isNotBlank() } ?: return
                }
                messages[messageId] = ChatMessageItem(
                    id = messageId,
                    senderId = senderId,
                    senderAlias = data.senderAlias.orEmpty(),
                    text = text,
                    isSpeech = data.isSpeech == true,
                    timestamp = timestamp,
                    isMine = senderId == myUid,
                    isDeleted = isDeleted,
                )
                trySend(messages.values.sortedBy { it.timestamp })
            }

            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleSnapshot(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleSnapshot(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val messageId = snapshot.key ?: return
                if (messages.remove(messageId) != null) {
                    trySend(messages.values.sortedBy { it.timestamp })
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                SecureLog.e(TAG, "Messages listener cancelled: ${error.message}")
            }
        }

        messagesRef.addChildEventListener(listener)
        awaitClose { messagesRef.removeEventListener(listener) }
    }

    suspend fun sendMessage(
        roomId: String,
        senderId: String,
        senderAlias: String,
        text: String,
        isSpeech: Boolean,
    ) {
        if (!isRoomMessagingAllowed(roomId)) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val now = System.currentTimeMillis()
        val messageRef = database.child(ProximityChatConstants.MESSAGES_PATH).child(roomId).push()
        val message = ChatMessageData(
            senderId = senderId,
            senderAlias = senderAlias,
            text = trimmed,
            isSpeech = isSpeech,
            timestamp = now,
        )
        messageRef.setValueOnce(message)
        database.child(ProximityChatConstants.ROOMS_PATH)
            .child(roomId)
            .child("lastMessageAt")
            .setValueOnce(now)
        database.child(ProximityChatConstants.ROOMS_PATH)
            .child(roomId)
            .child("lastMessageSenderId")
            .setValueOnce(senderId)
        updateLastReadAt(roomId, senderId, now)
    }

    suspend fun deleteMessage(roomId: String, messageId: String, myUid: String) {
        val messageRef = database.child(ProximityChatConstants.MESSAGES_PATH)
            .child(roomId)
            .child(messageId)
        val data = messageRef.getOnce().getValue(ChatMessageData::class.java) ?: return
        val senderId = data.senderId ?: return
        if (senderId != myUid) return
        if (data.deleted == true || data.text == ProximityChatConstants.REMOVED_MESSAGE_TEXT) return
        messageRef.updateChildrenOnce(
            mapOf(
                "deleted" to true,
                "text" to ProximityChatConstants.REMOVED_MESSAGE_TEXT,
            ),
        )
    }

    suspend fun updateLastReadAt(roomId: String, uid: String, readAt: Long) {
        val participantRef = database.child(ProximityChatConstants.ROOMS_PATH)
            .child(roomId)
            .child("participants")
            .child(uid)
        val current = participantRef.child("lastReadAt").getOnce()
            .getValue(Long::class.java) ?: 0L
        if (readAt > current) {
            participantRef.child("lastReadAt").setValueOnce(readAt)
        }
    }

    suspend fun getRoomDisplayName(roomId: String, myUid: String): String {
        val roomSnapshot = database.child(ProximityChatConstants.ROOMS_PATH).child(roomId).getOnce()
        val room = roomSnapshot.getValue(ChatRoomData::class.java)
        if (room?.type == ProximityChatConstants.ROOM_TYPE_GROUP) {
            return room.name?.takeIf { it.isNotBlank() } ?: roomId
        }
        return roomSnapshot.child("participants").children
            .firstOrNull { it.key != myUid }
            ?.child("alias")
            ?.getValue(String::class.java)
            ?.takeIf { it.isNotBlank() }
            ?: roomId
    }

    suspend fun ensureAuthenticated(): String = FirebaseUtils.ensureReady()

    private suspend fun linkUserToRoom(uid: String, roomId: String, timestamp: Long) {
        database.child(ProximityChatConstants.USER_ROOMS_PATH)
            .child(uid)
            .child(roomId)
            .setValueOnce(timestamp)
    }

    private fun isFresh(timestamp: Long?): Boolean {
        if (timestamp == null) return false
        return System.currentTimeMillis() - timestamp < ProximityChatConstants.PRESENCE_STALE_MS
    }

    private suspend fun com.google.firebase.database.DatabaseReference.getOnce(): DataSnapshot =
        suspendCancellableCoroutine { continuation ->
            addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    continuation.resume(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resumeWithException(error.toException())
                }
            })
        }

    private suspend fun com.google.firebase.database.DatabaseReference.setValueOnce(value: Any?): Unit =
        suspendCancellableCoroutine { continuation ->
            setValue(value)
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    private suspend fun com.google.firebase.database.DatabaseReference.removeValueOnce(): Unit =
        suspendCancellableCoroutine { continuation ->
            removeValue()
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    private suspend fun com.google.firebase.database.DatabaseReference.updateChildrenOnce(
        updates: Map<String, Any?>,
    ): Unit = suspendCancellableCoroutine { continuation ->
        updateChildren(updates)
            .addOnSuccessListener { continuation.resume(Unit) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    private companion object {
        const val TAG = "ProximityChatRepository"
    }
}
