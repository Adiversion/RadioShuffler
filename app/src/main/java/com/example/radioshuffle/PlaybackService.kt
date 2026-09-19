package com.example.radioshuffle

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var validPlaces: List<PlaceRecord> = emptyList()

    private val api: RadioGardenService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .header("Referer", "https://radio.garden/")
                    .header("Origin", "https://radio.garden")
                    .build()
                chain.proceed(request)
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://radio.garden/api/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RadioGardenService::class.java)
    }

    override fun onCreate() {
        super.onCreate()

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        // Request ICY stream headers to parse live playing track titles
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Intercept Next / Previous button presses from Bluetooth or Car units
        val callback = object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                return super.onPlayerCommandRequest(session, controllerInfo, playerCommand)
            }

            // Hook Next Track (skip forward button) to shuffle
            override fun onSeekToNext(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<androidx.media3.session.SessionResult> {
                shuffleBackground(session.player)
                return Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                )
            }

            // Hook Previous Track button to shuffle
            override fun onSeekToPrevious(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<androidx.media3.session.SessionResult> {
                shuffleBackground(session.player)
                return Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                )
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(callback)
            .build()
    }

    private fun shuffleBackground(player: androidx.media3.common.Player) {
        serviceScope.launch {
            try {
                if (validPlaces.isEmpty()) {
                    val envelope = withContext(Dispatchers.IO) { api.fetchPlaces() }
                    validPlaces = envelope.data?.list?.filter { (it.size ?: 0) > 0 } ?: emptyList()
                }
                if (validPlaces.isEmpty()) return@launch

                for (attempt in 0..4) {
                    val place = validPlaces.random()
                    val page = withContext(Dispatchers.IO) {
                        try { api.fetchChannelsForPlace(place.id) } catch (e: Exception) { null }
                    }

                    val stations = page?.data?.content
                        ?.flatMap { it.items ?: emptyList() }
                        ?.mapNotNull { it.page }
                        ?.filter { !it.url.isNullOrBlank() }
                        ?: emptyList()

                    if (stations.isNotEmpty()) {
                        val station = stations.random()
                        val channelId = station.url!!.trimEnd('/').substringAfterLast('/')
                        val title = station.title ?: "Radio Station"
                        val location = "${station.place?.title ?: place.title}, ${station.country?.title ?: place.country}"
                        val streamUrl = "https://radio.garden/api/ara/content/listen/$channelId/channel.mp3"

                        val mediaItem = MediaItem.Builder()
                            .setUri(streamUrl)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setArtist(location)
                                    .build()
                            )
                            .build()

                        player.stop()
                        player.clearMediaItems()
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.play()
                        break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
