package com.wim4you.intervene.ui.home

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.wim4you.intervene.AppPreferences

object PatrolAlertSoundPlayer {

    fun play(context: Context) {
        val mediaPlayer = MediaPlayer.create(context, Settings.System.DEFAULT_NOTIFICATION_URI) ?: return
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
            mediaPlayer.isLooping = false
            mediaPlayer.start()
            Handler(Looper.getMainLooper()).postDelayed({
                mediaPlayer.stop()
                mediaPlayer.release()
            }, 10_000)
        } catch (_: Exception) {
            mediaPlayer.release()
        }
    }

    fun playChatNotification(context: Context) {
        if (!AppPreferences.isChatRingingSoundEnabled(context)) return
        play(context)
    }
}
