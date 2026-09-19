package com.example.radioshuffle

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

// --- Models ---
data class PlacesEnvelope(@SerializedName("data") val data: PlacesData?)
data class PlacesData(@SerializedName("list") val list: List<PlaceRecord>?)
data class PlaceRecord(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("size") val size: Int?
)

data class ChannelsEnvelope(@SerializedName("data") val data: ChannelsData?)
data class ChannelsData(@SerializedName("content") val content: List<ContentBlock>?)
data class ContentBlock(@SerializedName("items") val items: List<ItemWrapper>?)
data class ItemWrapper(@SerializedName("page") val page: PageDetails?)
data class PageDetails(
    @SerializedName("url") val url: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("place") val place: PlaceInfo?,
    @SerializedName("country") val country: CountryInfo?
)
data class PlaceInfo(@SerializedName("title") val title: String?)
data class CountryInfo(@SerializedName("title") val title: String?)

// --- Service ---
interface RadioGardenService {
    @GET("ara/content/places")
    suspend fun fetchPlaces(): PlacesEnvelope

    @GET("ara/content/page/{placeId}/channels")
    suspend fun fetchChannelsForPlace(@Path("placeId") placeId: String): ChannelsEnvelope

    companion object {
        fun create(): RadioGardenService {
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

            return Retrofit.Builder()
                .baseUrl("https://radio.garden/api/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RadioGardenService::class.java)
        }
    }
}

// --- State ---
sealed class RadioUiState {
    object Idle : RadioUiState()
    object Loading : RadioUiState()
    data class Playing(
        val title: String,
        val city: String,
        val country: String,
        val currentTrack: String?,
        val isPlaying: Boolean
    ) : RadioUiState()
    data class Error(val message: String) : RadioUiState()
}

// --- ViewModel with Session State Synchronization ---
class RadioViewModel : ViewModel() {
    private val service = RadioGardenService.create()
    private var controller: MediaController? = null
    private var validPlaces: List<PlaceRecord> = emptyList()

    private val _uiState = MutableStateFlow<RadioUiState>(RadioUiState.Idle)
    val uiState: StateFlow<RadioUiState> = _uiState

    fun setController(mediaController: MediaController) {
        this.controller = mediaController

        // ⭐ SYNC ON REOPEN: Check if the background service is already streaming
        val currentItem = mediaController.currentMediaItem
        if (currentItem != null) {
            val meta = currentItem.mediaMetadata
            val stationTitle = meta.title?.toString() ?: "Radio Station"
            val locationParts = (meta.artist?.toString() ?: "").split(", ")
            val city = locationParts.getOrNull(0) ?: ""
            val country = locationParts.getOrNull(1) ?: ""

            _uiState.value = RadioUiState.Playing(
                title = stationTitle,
                city = city,
                country = country,
                currentTrack = null,
                isPlaying = mediaController.isPlaying
            )
        }

        mediaController.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val current = _uiState.value
                if (current is RadioUiState.Playing) {
                    _uiState.value = current.copy(isPlaying = isPlaying)
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val current = _uiState.value
                val streamTitle = mediaMetadata.title?.toString()
                if (current is RadioUiState.Playing && !streamTitle.isNullOrBlank() && streamTitle != current.title) {
                    _uiState.value = current.copy(currentTrack = streamTitle)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.let { meta ->
                    val stationTitle = meta.title?.toString() ?: "Radio Station"
                    val locationParts = (meta.artist?.toString() ?: "").split(", ")
                    val city = locationParts.getOrNull(0) ?: ""
                    val country = locationParts.getOrNull(1) ?: ""

                    _uiState.value = RadioUiState.Playing(
                        title = stationTitle,
                        city = city,
                        country = country,
                        currentTrack = null,
                        isPlaying = true
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _uiState.value = RadioUiState.Error("Stream unreachable. Tap Shuffle again!")
            }
        })
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun nextStation() {
        controller?.seekToNext()
    }

    fun shuffle() {
        viewModelScope.launch {
            _uiState.value = RadioUiState.Loading
            try {
                if (validPlaces.isEmpty()) {
                    val envelope = withContext(Dispatchers.IO) { service.fetchPlaces() }
                    validPlaces = envelope.data?.list?.filter { (it.size ?: 0) > 0 } ?: emptyList()
                }

                if (validPlaces.isEmpty()) {
                    _uiState.value = RadioUiState.Error("Could not fetch global directory.")
                    return@launch
                }

                var resolvedChannelId: String? = null
                var resolvedTitle: String? = null
                var resolvedCity: String? = null
                var resolvedCountry: String? = null

                for (attempt in 0..5) {
                    val randomPlace = validPlaces.random()
                    val page = withContext(Dispatchers.IO) {
                        try {
                            service.fetchChannelsForPlace(randomPlace.id)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    val validStations = page?.data?.content
                        ?.flatMap { it.items ?: emptyList() }
                        ?.mapNotNull { it.page }
                        ?.filter { !it.url.isNullOrBlank() }
                        ?: emptyList()

                    if (validStations.isNotEmpty()) {
                        val station = validStations.random()
                        val channelId = station.url!!.trimEnd('/').substringAfterLast('/')

                        if (channelId.isNotBlank()) {
                            resolvedChannelId = channelId
                            resolvedTitle = station.title ?: "World Radio"
                            resolvedCity = station.place?.title ?: randomPlace.title ?: "Unknown City"
                            resolvedCountry = station.country?.title ?: randomPlace.country ?: "Worldwide"
                            break
                        }
                    }
                }

                if (resolvedChannelId == null) {
                    _uiState.value = RadioUiState.Error("Station query timed out. Tap Shuffle again.")
                    return@launch
                }

                val streamUrl = "https://radio.garden/api/ara/content/listen/$resolvedChannelId/channel.mp3"
                val locationFull = "$resolvedCity, $resolvedCountry"

                controller?.let { player ->
                    val metadata = MediaMetadata.Builder()
                        .setTitle(resolvedTitle)
                        .setArtist(locationFull)
                        .build()

                    val mediaItem = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaMetadata(metadata)
                        .build()

                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                }

                _uiState.value = RadioUiState.Playing(
                    title = resolvedTitle ?: "Radio Station",
                    city = resolvedCity ?: "",
                    country = resolvedCountry ?: "",
                    currentTrack = null,
                    isPlaying = true
                )

            } catch (e: Exception) {
                _uiState.value = RadioUiState.Error("Connection error: ${e.localizedMessage ?: "Unknown"}")
            }
        }
    }
}

// --- Activity ---
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val viewModel: RadioViewModel = viewModel()

            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                controllerFuture?.addListener({
                    controllerFuture?.get()?.let { viewModel.setController(it) }
                }, MoreExecutors.directExecutor())
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0C0F14)
            ) {
                ModernRadioScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

@Composable
fun ModernRadioScreen(viewModel: RadioViewModel) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E676))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "RADIO GARDEN SHUFFLER",
                color = Color(0xFF8E9BAE),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222B38))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (val current = state) {
                    is RadioUiState.Idle -> {
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1B222E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📻", fontSize = 34.sp)
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Ready to Explore",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap Shuffle or use your Bluetooth headset to tune in.",
                            color = Color(0xFF7E8B9B),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    is RadioUiState.Loading -> {
                        CircularProgressIndicator(
                            color = Color(0xFF00E676),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Tuning global frequencies...",
                            color = Color(0xFF8E9BAE),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    is RadioUiState.Playing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color(0x1A00E676), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (current.isPlaying) "LIVE STREAM" else "PAUSED",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = current.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "📍 ${current.city}, ${current.country}",
                            color = Color(0xFF00E676),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (!current.currentTrack.isNullOrBlank()) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2330)),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🎵", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = current.currentTrack,
                                        color = Color(0xFFE0E6ED),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(
                                onClick = { viewModel.togglePlayPause() },
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0xFF222C3A), Color(0xFF18202B))
                                        )
                                    )
                                    .border(1.5.dp, Color(0xFF2F3C4E), CircleShape)
                            ) {
                                Text(
                                    text = if (current.isPlaying) "❚❚" else "▶",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(20.dp))

                            IconButton(
                                onClick = { viewModel.nextStation() },
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1A222D))
                                    .border(1.dp, Color(0xFF2B3645), CircleShape)
                            ) {
                                Text(
                                    text = "⏭",
                                    color = Color(0xFF00E676),
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }

                    is RadioUiState.Error -> {
                        Text(
                            text = current.message,
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Button(
            onClick = { viewModel.shuffle() },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00E676),
                contentColor = Color(0xFF0A120D)
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🎲", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Shuffle Station",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
