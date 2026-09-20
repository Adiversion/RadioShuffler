package com.example.radioshuffle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
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
    private var metadataProbeJob: Job? = null
    private var consecutiveAutoSkips = 0
    private var pendingShuffleCue = false
    private var currentStation: ResolvedStation? = null
    @Volatile
    private var currentTrackTitle: String? = null
    private var forwardingPlayer: RadioForwardingPlayer? = null

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

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val basePlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val initialSession = basePlayer.audioSessionId
        if (initialSession != 0) {
            openAudioEffectSession(initialSession)
            EqualizerHelper.attachSession(applicationContext, initialSession)
        }

        basePlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                openAudioEffectSession(audioSessionId)
                EqualizerHelper.attachSession(applicationContext, audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newChannelId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
                    ?: mediaItem?.mediaMetadata?.description?.toString()?.takeIf { it.isNotBlank() }

                // Only ignore if this is an in-place metadata update for the exact same active station
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                    newChannelId != null &&
                    newChannelId == currentStation?.channelId &&
                    currentTrackTitle != null
                ) {
                    return
                }
                currentTrackTitle = null
                metadataProbeJob?.cancel()
                val emptyOrStation = mediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
                basePlayer.playlistMetadata = emptyOrStation
                forwardingPlayer?.notifyMetadataChanged(emptyOrStation)
                if (mediaItem != null) {
                    scheduleStationHealthCheck(basePlayer)
                    scheduleMetadataProbe(basePlayer, mediaItem)
                }
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    when (entry) {
                        is IcyInfo -> {
                            val streamTitle = entry.title?.trim()
                            if (!streamTitle.isNullOrBlank()) {
                                updateNowPlayingTrack(basePlayer, streamTitle)
                            }
                        }
                        is TextInformationFrame -> {
                            if (entry.id == "TIT2") {
                                val songTitle = entry.values.firstOrNull()?.trim()
                                if (!songTitle.isNullOrBlank()) {
                                    updateNowPlayingTrack(basePlayer, songTitle)
                                }
                            }
                        }
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    // Manual pause by user: CANCEL all auto-skip / health timers immediately
                    stationHealthJob?.cancel()
                    consecutiveAutoSkips = 0
                } else if (basePlayer.playbackState == Player.STATE_BUFFERING || basePlayer.playbackState == Player.STATE_IDLE) {
                    scheduleStationHealthCheck(basePlayer)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (basePlayer.playWhenReady) {
                            scheduleStationHealthCheck(basePlayer)
                        }
                    }
                    Player.STATE_READY -> {
                        stationHealthJob?.cancel()
                        consecutiveAutoSkips = 0
                        if (pendingShuffleCue) {
                            pendingShuffleCue = false
                            SoundFeedback.playShuffleConnected()
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (basePlayer.playWhenReady) {
                            basePlayer.seekToDefaultPosition()
                            basePlayer.prepare()
                            basePlayer.play()
                        }
                    }
                    Player.STATE_IDLE -> {
                        if (basePlayer.playWhenReady) {
                            scheduleStationHealthCheck(basePlayer)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!basePlayer.playWhenReady) return
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

        val playerWrapper = RadioForwardingPlayer(basePlayer)
        forwardingPlayer = playerWrapper

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
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

        mediaSession = MediaSession.Builder(this, playerWrapper)
            .setSessionActivity(pendingIntent)
            .setCallback(callback)
            .build()
    }

    private inner class RadioForwardingPlayer(player: Player) : ForwardingPlayer(player) {
        private val customListeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            customListeners.add(listener)
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            customListeners.remove(listener)
            super.removeListener(listener)
        }

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

        override fun getMediaMetadata(): MediaMetadata {
            val base = super.getMediaMetadata()
            val currentTrack = currentTrackTitle
            val station = currentStation
            if (!currentTrack.isNullOrBlank() && station != null) {
                val extras = (base.extras ?: Bundle()).apply {
                    putString("station_title", station.title)
                    putString("track_title", currentTrack)
                }
                return base.buildUpon()
                    .setTitle(currentTrack)
                    .setDisplayTitle(currentTrack)
                    .setAlbumTitle(station.title)
                    .setArtist(station.title)
                    .setSubtitle(station.location)
                    .setExtras(extras)
                    .build()
            }
            return base
        }

        override fun getCurrentMediaItem(): MediaItem? {
            val item = super.getCurrentMediaItem() ?: return null
            val currentTrack = currentTrackTitle
            val station = currentStation
            if (!currentTrack.isNullOrBlank() && station != null) {
                return item.buildUpon()
                    .setMediaMetadata(getMediaMetadata())
                    .build()
            }
            return item
        }

        fun notifyMetadataChanged(metadata: MediaMetadata) {
            for (listener in customListeners) {
                try {
                    listener.onMediaMetadataChanged(metadata)
                    listener.onPlaylistMetadataChanged(metadata)
                } catch (_: Exception) {}
            }
        }
    }

    private fun shuffleBackground(player: Player, playCue: Boolean) {
        shuffleJob?.cancel()
        metadataProbeJob?.cancel()
        currentTrackTitle = null
        shuffleJob = serviceScope.launch {
            try {
                val station = withContext(Dispatchers.IO) {
                    repository.nextStation()
                } ?: return@launch

                currentStation = station
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
        // If user manually paused (playWhenReady == false), NEVER run health check or auto-skip!
        if (!player.playWhenReady) return

        stationHealthJob = serviceScope.launch {
            delay(STATION_START_GRACE_MS)

            // ONLY consider unhealthy if user still wants to play (playWhenReady == true)
            // AND the stream is stuck buffering or in idle/error state!
            val stationStuckBuffering = player.playWhenReady &&
                player.currentMediaItem != null &&
                (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE)

            if (stationStuckBuffering && consecutiveAutoSkips < MAX_AUTO_SKIPS) {
                consecutiveAutoSkips += 1
                shuffleBackground(player, playCue = false)
            } else if (!stationStuckBuffering) {
                consecutiveAutoSkips = 0
            }
        }
    }

    private fun updateNowPlayingTrack(player: Player, trackTitle: String) {
        val trimmed = trackTitle.trim()
        if (trimmed.isBlank() || trimmed.equals(currentTrackTitle, ignoreCase = true)) return
        currentTrackTitle = trimmed
        metadataProbeJob?.cancel()

        val currentItem = player.currentMediaItem ?: return
        val currentMeta = currentItem.mediaMetadata
        val stationTitle = currentMeta.albumTitle?.toString()
            ?: currentMeta.extras?.getString("station_title")
            ?: currentStation?.title
            ?: "Radio Station"
        val stationLocation = currentMeta.subtitle?.toString()
            ?: currentStation?.location
            ?: ""

        if (trimmed.equals(stationTitle, ignoreCase = true)) return

        val updatedExtras = (currentMeta.extras ?: Bundle()).apply {
            putString("station_title", stationTitle)
            putString("track_title", trimmed)
        }

        val updatedMetadata = currentMeta.buildUpon()
            .setTitle(trimmed)
            .setDisplayTitle(trimmed)
            .setAlbumTitle(stationTitle)
            .setArtist(stationTitle)
            .setSubtitle(stationLocation)
            .setExtras(updatedExtras)
            .build()

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        try {
            if (player.currentMediaItemIndex >= 0) {
                player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
            }
        } catch (_: Exception) {
        }
        player.playlistMetadata = updatedMetadata
        forwardingPlayer?.notifyMetadataChanged(updatedMetadata)
    }

    private fun scheduleMetadataProbe(player: Player, mediaItem: MediaItem) {
        metadataProbeJob?.cancel()
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: mediaItem.requestMetadata.mediaUri?.toString()
        if (uri.isNullOrBlank()) return

        metadataProbeJob = serviceScope.launch {
            delay(3_500)
            if (currentTrackTitle.isNullOrBlank() && player.currentMediaItem?.mediaId == mediaItem.mediaId) {
                val probedTitle = IcyStreamMetadata.probeTrackTitle(uri)
                if (!probedTitle.isNullOrBlank() && currentTrackTitle.isNullOrBlank() && player.currentMediaItem?.mediaId == mediaItem.mediaId) {
                    updateNowPlayingTrack(player, probedTitle)
                }
            }
        }
    }

    private fun ResolvedStation.toMediaItem(): MediaItem {
        val artworkBytes = StationArtwork.getArtworkData(this@PlaybackService)
        val artworkUri = StationArtwork.getArtworkUri(this@PlaybackService)

        val extras = Bundle().apply {
            putString("station_title", title)
            putString("station_city", city)
            putString("station_country", country)
        }

        val metaBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setAlbumTitle(title)
            .setArtist(location)
            .setSubtitle(location)
            .setDescription(channelId)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setArtworkUri(artworkUri)
            .setExtras(extras)

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
        metadataProbeJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            val sessionId = (player as? ExoPlayer)?.audioSessionId ?: 0
            closeAudioEffectSession(sessionId)
            EqualizerHelper.release()
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun openAudioEffectSession(sessionId: Int) {
        if (sessionId != 0) {
            try {
                val intent = Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC)
                }
                sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }

    private fun closeAudioEffectSession(sessionId: Int) {
        if (sessionId != 0) {
            try {
                val intent = Intent(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                }
                sendBroadcast(intent)
            } catch (_: Exception) {}
        }
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
