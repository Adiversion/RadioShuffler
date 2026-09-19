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
data class PlacesResponse(val data: PlacesData?)
data class PlacesData(val list: List<PlaceItem>?)
data class PlaceItem(val id: String, val title: String, val country: String, val size: Int?)

data class ChannelsResponse(val data: ChannelsData?)
data class ChannelsData(val content: List<ChannelBlock>?)
data class ChannelBlock(val itemsType: String?, val items: List<StationItem>?)
data class StationItem(val href: String?, val title: String?)

// --- API ---
interface RadioGardenApi {
    @GET("ara/content/places")
    suspend fun getPlaces(): PlacesResponse

    @GET("ara/content/page/{placeId}/channels")
    suspend fun getPlaceChannels(@Path("placeId") placeId: String): ChannelsResponse

    companion object {
        fun create(): RadioGardenApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) RadioGardenShuffler/1.0")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl("https://radio.garden/api/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RadioGardenApi::class.java)
        }
    }
}

// --- ViewModel ---
sealed class RadioUiState {
    object Idle : RadioUiState()
    object Loading : RadioUiState()
    data class Playing(val title: String, val location: String, val isPlaying: Boolean) : RadioUiState()
    data class Error(val message: String) : RadioUiState()
}

class RadioViewModel : ViewModel() {
    private val api = RadioGardenApi.create()
    private var controller: MediaController? = null
    private var viablePlaces: List<PlaceItem> = emptyList()

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
                _uiState.value = RadioUiState.Error("Station offline. Tap Shuffle to try another.")
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
                if (viablePlaces.isEmpty()) {
                    val placesRes = withContext(Dispatchers.IO) { api.getPlaces() }
                    viablePlaces = placesRes.data?.list?.filter { (it.size ?: 0) > 0 } ?: emptyList()
                }

                if (viablePlaces.isEmpty()) {
                    _uiState.value = RadioUiState.Error("Failed to fetch places directory.")
                    return@launch
                }

                var selectedStation: StationItem? = null
                var selectedPlace: PlaceItem? = null

                for (attempt in 0..2) {
                    val place = viablePlaces.random()
                    val channelsRes = withContext(Dispatchers.IO) { api.getPlaceChannels(place.id) }

                    val stations = channelsRes.data?.content
                        ?.filter { it.itemsType == "channel" }
                        ?.flatMap { it.items ?: emptyList() }
                        ?.filter { !it.href.isNullOrBlank() && !it.title.isNullOrBlank() }

                    if (!stations.isNullOrEmpty()) {
                        selectedStation = stations.random()
                        selectedPlace = place
                        break
                    }
                }

                if (selectedStation == null || selectedPlace == null) {
                    _uiState.value = RadioUiState.Error("No active stations found. Try again.")
                    return@launch
                }

                val channelId = selectedStation.href!!.trimEnd('/').substringAfterLast('/')
                val streamUrl = "https://radio.garden/api/ara/content/listen/$channelId/channel.mp3"
                val locationName = "${selectedPlace.title}, ${selectedPlace.country}"
                val stationTitle = selectedStation.title ?: "Unknown Station"

                controller?.let { player ->
                    val metadata = MediaMetadata.Builder()
                        .setTitle(stationTitle)
                        .setArtist(locationName)
                        .build()

                    val item = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaMetadata(metadata)
                        .build()

                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                }

                _uiState.value = RadioUiState.Playing(
                    title = stationTitle,
                    location = locationName,
                    isPlaying = true
                )
            } catch (e: Exception) {
                _uiState.value = RadioUiState.Error("Network error. Try again.")
            }
        }
    }
}

// --- Activity & UI ---
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val viewModel: RadioViewModel = viewModel()

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                            text = "Tap the Shuffle button to start playing a random station.",
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp
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
                            Spacer(modifier = Modifier.height(10.dp))
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
