package com.example.radioshuffle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repository = RadioGardenRepository()
    private var shuffleJob: Job? = null
    private var stationHealthJob: Job? = null
    private var consecutiveAutoSkips = 0
    private var pendingShuffleCue = false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_radio_notification)
        setMediaNotificationProvider(notificationProvider)

        serviceScope.launch(Dispatchers.IO) {
            repository.warmUp()
        }

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Icy-MetaData" to "1",
                    "Referer" to "https://radio.garden/",
                    "Origin" to "https://radio.garden"
                )
            )
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val basePlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        basePlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    scheduleStationHealthCheck(basePlayer)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        consecutiveAutoSkips = 0
                        if (pendingShuffleCue) {
                            pendingShuffleCue = false
                            playShuffleCompleteCue()
                        }
                    }
                    Player.STATE_ENDED -> shuffleBackground(basePlayer, playCue = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                shuffleBackground(basePlayer, playCue = false)
            }
        })

        val forwardingPlayer = object : ForwardingPlayer(basePlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                shuffleBackground(this, playCue = true)
            }

            override fun seekToNextMediaItem() {
                shuffleBackground(this, playCue = true)
            }

            override fun seekToPrevious() {
                shuffleBackground(this, playCue = true)
            }

            override fun seekToPreviousMediaItem() {
                shuffleBackground(this, playCue = true)
            }
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callback = object : MediaSession.Callback {
            override fun onPlayerCommandRequest(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                if (playerCommand == Player.COMMAND_SEEK_TO_NEXT ||
                    playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    shuffleBackground(session.player, playCue = true)
                    return SessionResult.RESULT_INFO_SKIPPED
                }
                return super.onPlayerCommandRequest(session, controllerInfo, playerCommand)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(callback)
            .build()
    }

    private fun shuffleBackground(player: Player, playCue: Boolean) {
        shuffleJob?.cancel()
        shuffleJob = serviceScope.launch {
            try {
                val station = withContext(Dispatchers.IO) {
                    repository.nextStation()
                } ?: return@launch

                player.stop()
                player.clearMediaItems()
                player.setMediaItem(station.toMediaItem())
                pendingShuffleCue = pendingShuffleCue || playCue
                player.prepare()
                player.play()

                scheduleStationHealthCheck(player)
            } catch (_: Exception) {
                // MediaSession controllers should not crash the playback service.
            }
        }
    }

    private fun scheduleStationHealthCheck(player: Player) {
        stationHealthJob?.cancel()
        stationHealthJob = serviceScope.launch {
            delay(STATION_START_GRACE_MS)

            val stationStillUnhealthy = player.currentMediaItem != null &&
                (player.playbackState == Player.STATE_BUFFERING ||
                    player.playbackState == Player.STATE_IDLE ||
                    !player.isPlaying)

            if (stationStillUnhealthy && consecutiveAutoSkips < MAX_AUTO_SKIPS) {
                consecutiveAutoSkips += 1
                shuffleBackground(player, playCue = false)
            } else if (!stationStillUnhealthy) {
                consecutiveAutoSkips = 0
            }
        }
    }

    private fun playShuffleCompleteCue() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            serviceScope.launch {
                delay(250)
                tone.release()
            }
        } catch (_: RuntimeException) {
            // Some devices reject ToneGenerator while audio focus is changing.
        }
    }

    private fun ResolvedStation.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(location)
                    .build()
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        shuffleJob?.cancel()
        stationHealthJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Radio Stream Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Radio stream playback and lock screen controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "radioshuffler_playback_channel"
        const val NOTIFICATION_ID = 1001
        private const val STATION_START_GRACE_MS = 18_000L
        private const val MAX_AUTO_SKIPS = 3
    }
}
