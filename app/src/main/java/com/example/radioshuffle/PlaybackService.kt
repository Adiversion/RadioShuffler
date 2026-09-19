package com.example.radioshuffle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
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
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
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

        val defaultNotificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        defaultNotificationProvider.setSmallIcon(R.drawable.ic_radio_notification)

        val customNotificationProvider = CustomMediaNotificationProvider(defaultNotificationProvider)
        setMediaNotificationProvider(customNotificationProvider)

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
                            SoundFeedback.playShuffleConnected()
                        }
                    }
                    Player.STATE_ENDED -> {
                        // Live radio streams should reconnect rather than discard the user's station
                        basePlayer.seekToDefaultPosition()
                        basePlayer.prepare()
                        basePlayer.play()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (consecutiveAutoSkips < 1) {
                    consecutiveAutoSkips += 1
                    basePlayer.prepare()
                    basePlayer.play()
                } else if (consecutiveAutoSkips < MAX_AUTO_SKIPS) {
                    consecutiveAutoSkips += 1
                    shuffleBackground(basePlayer, playCue = false)
                }
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
                SoundFeedback.playShuffleTriggered()
                shuffleBackground(this, playCue = true)
            }

            override fun seekToNextMediaItem() {
                SoundFeedback.playShuffleTriggered()
                shuffleBackground(this, playCue = true)
            }

            override fun seekToPrevious() {
                SoundFeedback.playShuffleTriggered()
                shuffleBackground(this, playCue = true)
            }

            override fun seekToPreviousMediaItem() {
                SoundFeedback.playShuffleTriggered()
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
                    SoundFeedback.playShuffleTriggered()
                    shuffleBackground(session.player, playCue = true)
                    return SessionResult.RESULT_INFO_SKIPPED
                }
                return super.onPlayerCommandRequest(session, controllerInfo, playerCommand)
            }

            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                @Suppress("DEPRECATION")
                val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                        KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                            SoundFeedback.playShuffleTriggered()
                            shuffleBackground(session.player, playCue = true)
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
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

    private fun ResolvedStation.toMediaItem(): MediaItem {
        val artworkBytes = StationArtwork.getArtworkData(this@PlaybackService)
        val artworkUri = StationArtwork.getArtworkUri(this@PlaybackService)

        val metaBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(location)
            .setSubtitle(location)
            .setDescription(channelId)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setArtworkUri(artworkUri)

        if (artworkBytes != null) {
            metaBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setMediaId(channelId)
            .setUri(streamUrl)
            .setMediaMetadata(metaBuilder.build())
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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Radio stream playback and lock screen controls"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "radioshuffler_playback_channel_v3"
        const val NOTIFICATION_ID = 1001
        private const val STATION_START_GRACE_MS = 18_000L
        private const val MAX_AUTO_SKIPS = 3
    }
}

private class CustomMediaNotificationProvider(
    private val defaultProvider: DefaultMediaNotificationProvider
) : MediaNotification.Provider {
    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedListener: MediaNotification.Provider.Callback
    ): MediaNotification {
        val mediaNotification = defaultProvider.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            onNotificationChangedListener
        )
        mediaNotification.notification.visibility = Notification.VISIBILITY_PUBLIC
        mediaNotification.notification.category = Notification.CATEGORY_TRANSPORT
        return mediaNotification
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: android.os.Bundle
    ): Boolean {
        return defaultProvider.handleCustomCommand(session, action, extras)
    }
}
