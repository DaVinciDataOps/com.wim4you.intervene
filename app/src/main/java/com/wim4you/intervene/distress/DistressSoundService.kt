package com.wim4you.intervene.distress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wim4you.intervene.Constants

class DistressSoundService: Service(), AudioManager.OnAudioFocusChangeListener {
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val notificationId = Constants.DISTRESS_SOUND_SERVICE_NOTIFICATION_ID
    private val channelId = Constants.DISTRESS_SOUND_SERVICE_CHANNEL_ID
    private lateinit var attributedContext: Context
    companion object {
        const val ACTION_PLAY_STATE_CHANGED = "com.wim4you.intervene.PLAY_STATE_CHANGED"
        const val EXTRA_IS_PLAYING = "is_playing"
        // LiveData to communicate service state to Activity/ViewModel
        val isPlaying = MutableLiveData(false)

        // Helper to start the service
        fun start(context: Context) {
            val intent = Intent(context, DistressSoundService::class.java)
            context.startForegroundService(intent)
        }

        // Helper to stop the service
        fun stop(context: Context) {
            val intent = Intent(context, DistressSoundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        attributedContext = createAttributionContext(Constants.DISTRESS_SOUND_CONTEXT_TAG)
        audioManager = attributedContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()
            // Create and show foreground notification
            val notification = createNotification()
            startForeground(notificationId, notification)

            // Start playing sound
            startDistressSound()
        }
        catch (e: Exception) {
            Log.e("DistressSoundService", "Error starting service onStartCommand", e)
        }
        return START_STICKY // Service restarts if killed by system
    }

    private fun startDistressSound() {
        //if (mediaPlayer?.isPlaying == true) return
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }

        val audioManager = attributedContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener(this)
            .build()
        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            createAndPlayMediaPlayer()
        }
    }

    private fun stopDistressSound() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null

            // Broadcast stopped state
            sendPlayStateBroadcast(false)
        } catch (e: Exception) {
            Log.e("DistressSoundService", "Error stopping MediaPlayer", e)
        }
    }

    private fun createAndPlayMediaPlayer() {
        try {
            val audioManager = attributedContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@DistressSoundService,
                    "android.resource://com.wim4you.intervene/raw/high_pitch_alarm".toUri())
                prepare()
                isLooping = true
                setVolume(1.0f, 1.0f)
                start()
                // Broadcast playing state
                sendPlayStateBroadcast(true)
            }
        } catch (e: Exception) {
            Log.e("DistressSoundService", "Error starting MediaPlayer", e)
        }
    }

    private fun sendPlayStateBroadcast(isPlaying: Boolean) {
        val intent = Intent(ACTION_PLAY_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, isPlaying)
        }
        LocalBroadcastManager.getInstance(attributedContext).sendBroadcast(intent)
    }

    private fun createNotificationChannel(){
        val channel = NotificationChannel(
            channelId,
            "Distress Sound Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Channel for Distress Sound Service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(attributedContext, channelId)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentTitle("Distress Alert Active")
            .setContentText("Distress sound is playing.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .build()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                stopDistressSound()
                stopSelf()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaPlayer?.pause()
            AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.start()
        }
    }

    override fun onDestroy() {
        stopDistressSound()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}