package com.wim4you.intervene.distressstream.webrtc

import com.wim4you.intervene.BuildConfig
import org.webrtc.PeerConnection

object WebRtcConfig {
    const val VIDEO_WIDTH = 640
    const val VIDEO_HEIGHT = 480
    const val VIDEO_FPS = 24

    fun iceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
        val turnUrl = BuildConfig.WEBRTC_TURN_URL.trim()
        if (turnUrl.isNotEmpty()) {
            val builder = PeerConnection.IceServer.builder(turnUrl)
            val username = BuildConfig.WEBRTC_TURN_USERNAME.trim()
            val password = BuildConfig.WEBRTC_TURN_PASSWORD
            if (username.isNotEmpty()) {
                builder.setUsername(username)
                builder.setPassword(password)
            }
            servers.add(builder.createIceServer())
        }
        return servers
    }
}
