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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// -----------------------------------------------------------------------------
// 1. DATA MODELS
// -----------------------------------------------------------------------------
data class PlacesEnvelope(
    @SerializedName("data") val data: PlacesData?
)

data class PlacesData(
    @SerializedName("list") val list: List<PlaceRecord>?
)

data class PlaceRecord(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("size") val size: Int?
)

data class ChannelPageEnvelope(
    @SerializedName("data") val data: ChannelPageData?
)

data class ChannelPageData(
    @SerializedName("content") val content: List<ContentSection>?
)

data class ContentSection(
    @SerializedName("itemsType") val itemsType: String?,
    @SerializedName("items") val items: List<StationRecord>?
)

data class StationRecord(
    @SerializedName("pageId") val pageId: String?,
    @SerializedName("href") val href: String?,
    @SerializedName("title") val title: String?
)

// -----------------------------------------------------------------------------
// 2. RETROFIT API SERVICE
// -----------------------------------------------------------------------------
interface RadioGardenService {
    @GET("ara/content/places")
    suspend fun fetchPlaces(): PlacesEnvelope

    @GET("ara/content/page/{placeId}/channels")
    suspend fun fetchChannelsForPlace(@Path("placeId") placeId: String): ChannelPageEnvelope

    companion object {
        fun create(): RadioGardenService {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Accept", "application/json")
                        .header("Referer", "https://radio.garden/")
                        .header("Origin", "https://radio.garden")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl("https://radio.garden/api/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RadioGardenService::class.java)
        }
    }
}

// -----------------------------------------------------------------------------
// 3. VIEWMODEL
// -----------------------------------------------------------------------------
sealed class RadioUiState {
    object Idle : RadioUiState()
    object Loading : RadioUiState()
    data class Playing(val title: String, val location: String, val isPlaying: Boolean) : RadioUiState()
    data class Error(val message: String) : RadioUiState()
}

class RadioViewModel : ViewModel() {
    private val service = RadioGardenService.create()
    private var controller: MediaController? = null
    private var validPlaces: List<PlaceRecord> = emptyList()

    private val _uiState = MutableStateFlow<RadioUiState>(RadioUiState.Idle)
    val uiState: StateFlow<RadioUiState> = _uiState

    fun setController(mediaController: MediaController) {
        this.controller = mediaController
        mediaController.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val current = _uiState.value
                if (current is RadioUiState.Playing) {
                    _uiState.value = current.copy(isPlaying = isPlaying)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _uiState.value = RadioUiState.Error("Station offline. Tap Shuffle again!")
            }
        })
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
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
                var chosenPlace: PlaceRecord? = null

                for (attempt in 0..6) {
                    val randomPlace = validPlaces.random()
                    val page = withContext(Dispatchers.IO) {
                        try {
                            service.fetchChannelsForPlace(randomPlace.id)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    val items = page?.data?.content
                        ?.flatMap { it.items ?: emptyList() }
                        ?: emptyList()

                    val validStations = items.filter { item ->
                        !item.pageId.isNullOrBlank() || !item.href.isNullOrBlank()
                    }

                    if (validStations.isNotEmpty()) {
                        val station = validStations.random()
                        val id = if (!station.pageId.isNullOrBlank()) {
                            station.pageId
                        } else {
                            station.href!!.trimEnd('/').substringAfterLast('/')
                        }

                        if (id.isNotBlank()) {
                            resolvedChannelId = id
                            resolvedTitle = station.title ?: "Radio Garden Station"
                            chosenPlace = randomPlace
                            break
                        }
                    }
                }

                if (resolvedChannelId == null || chosenPlace == null) {
                    _uiState.value = RadioUiState.Error("Station query timed out. Tap Shuffle again.")
                    return@launch
                }

                val streamUrl = "https://radio.garden/api/ara/content/listen/$resolvedChannelId/channel.mp3"
                val locationStr = "${chosenPlace.title ?: "Unknown City"}, ${chosenPlace.country ?: ""}"
                val finalTitle = resolvedTitle ?: "Radio Station"

                controller?.let { player ->
                    val metadata = MediaMetadata.Builder()
                        .setTitle(finalTitle)
                        .setArtist(locationStr)
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
                    title = finalTitle,
                    location = locationStr,
                    isPlaying = true
                )

            } catch (e: Exception) {
                _uiState.value = RadioUiState.Error("Network error: ${e.localizedMessage ?: "Unknown"}")
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. MAIN ACTIVITY & UI
// -----------------------------------------------------------------------------
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
                color = Color(0xFF101418)
            ) {
                RadioScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

@Composable
fun RadioScreen(viewModel: RadioViewModel) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "🌍 Radio Shuffler",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 48.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C222A))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                when (val current = state) {
                    is RadioUiState.Idle -> {
                        Text(
                            text = "Tap the Shuffle button to stream a random station from anywhere in the world.",
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            fontSize = 15.sp
                        )
                    }
                    is RadioUiState.Loading -> {
                        CircularProgressIndicator(color = Color(0xFF00E676))
                    }
                    is RadioUiState.Playing -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = current.title,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = current.location,
                                color = Color(0xFF00E676),
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            IconButton(
                                onClick = { viewModel.togglePlayPause() },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color(0xFF2E3846), CircleShape)
                            ) {
                                Text(
                                    text = if (current.isPlaying) "⏸" else "▶",
                                    color = Color.White,
                                    fontSize = 22.sp
                                )
                            }
                        }
                    }
                    is RadioUiState.Error -> {
                        Text(
                            text = current.message,
                            color = Color(0xFFFF6B6B),
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
                .padding(bottom = 32.dp)
                .height(58.dp),
            shape = RoundedCornerShape(29.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) {
            Text(
                text = "🎲 Shuffle Station",
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
