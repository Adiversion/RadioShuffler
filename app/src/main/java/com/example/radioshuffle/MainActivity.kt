package com.example.radioshuffle

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NavTab(val title: String, val iconRes: Int) {
    RADIO("Radio", R.drawable.ic_radio),
    SEARCH("Search", R.drawable.ic_search),
    LIBRARY("Library", R.drawable.ic_library)
}

enum class LibraryTab {
    FAVORITES,
    RECENTS
}

sealed class RadioUiState {
    object Idle : RadioUiState()
    data class Loading(val message: String = "Tuning global frequencies...") : RadioUiState()
    data class Playing(
        val title: String,
        val city: String,
        val country: String,
        val currentTrack: String?,
        val isPlaying: Boolean,
        val isBuffering: Boolean = false,
        val channelId: String = ""
    ) : RadioUiState()
    data class Error(val message: String) : RadioUiState()
}

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class Downloading(
        val versionName: String,
        val percent: Int = 0,
        val bytesRead: Long = 0L,
        val totalBytes: Long = 0L
    ) : UpdateUiState()
    data class OpeningInstaller(val versionName: String) : UpdateUiState()
    object NoUpdate : UpdateUiState()
    object NeedsInstallPermission : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RadioGardenRepository()
    private val favoritesManager = FavoritesManager(application)
    private var controller: MediaController? = null
    private var shuffleJob: Job? = null
    private var searchJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var updateJob: Job? = null
    private var lastQuery: String? = null
    private var currentStation: ResolvedStation? = null

    private val _uiState = MutableStateFlow<RadioUiState>(RadioUiState.Idle)
    val uiState: StateFlow<RadioUiState> = _uiState

    private val _activeQuery = MutableStateFlow<String?>(null)
    val activeQuery: StateFlow<String?> = _activeQuery

    private val _searchResults = MutableStateFlow<List<ResolvedStation>?>(null)
    val searchResults: StateFlow<List<ResolvedStation>?> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _favorites = MutableStateFlow<List<ResolvedStation>>(favoritesManager.getFavorites())
    val favorites: StateFlow<List<ResolvedStation>> = _favorites

    private val _recents = MutableStateFlow<List<ResolvedStation>>(favoritesManager.getRecents())
    val recents: StateFlow<List<ResolvedStation>> = _recents

    private val _isCurrentFavorite = MutableStateFlow<Boolean>(false)
    val isCurrentFavorite: StateFlow<Boolean> = _isCurrentFavorite

    private val _sleepTimerMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerMinutes: StateFlow<Int?> = _sleepTimerMinutes

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val current = _uiState.value
            if (current is RadioUiState.Playing) {
                _uiState.value = current.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING
                )
            }
        }

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
            val meta = mediaItem?.mediaMetadata
            val channelId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?: meta?.description?.toString()?.takeIf { it.isNotBlank() }

            if (meta != null) {
                val title = meta.title?.toString() ?: "Radio Station"
                val location = meta.artist?.toString() ?: ""
                val locParts = location.split(", ")
                val city = locParts.getOrNull(0).orEmpty()
                val country = locParts.getOrNull(1).orEmpty()

                if (!channelId.isNullOrBlank()) {
                    val activeStation = ResolvedStation(
                        channelId = channelId,
                        title = title,
                        city = city,
                        country = country
                    )
                    currentStation = activeStation
                    favoritesManager.addRecent(activeStation)
                    _recents.value = favoritesManager.getRecents()
                    _isCurrentFavorite.value = favoritesManager.isFavorite(channelId)
                }

                publishPlayingState(meta, isPlaying = true, isBuffering = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _uiState.value = RadioUiState.Loading("Station failed. Auto-skipping...")
        }
    }

    fun setController(mediaController: MediaController) {
        if (controller === mediaController) return

        controller?.removeListener(playerListener)
        controller = mediaController
        mediaController.addListener(playerListener)

        mediaController.currentMediaItem?.mediaMetadata?.let { meta ->
            publishPlayingState(
                metadata = meta,
                isPlaying = mediaController.isPlaying,
                isBuffering = mediaController.playbackState == Player.STATE_BUFFERING
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.warmUp()
        }
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun shuffle(query: String? = null, automatic: Boolean = false) {
        val cleanQuery = query?.trim()?.takeIf { it.isNotBlank() } ?: if (!automatic) _activeQuery.value else null
        if (!automatic) {
            SoundFeedback.playShuffleTriggered()
            _activeQuery.value = cleanQuery
            lastQuery = cleanQuery
            shuffleJob?.cancel()
        }

        shuffleJob = viewModelScope.launch {
            if (controller == null) {
                _uiState.value = RadioUiState.Error("Player is still starting. Try again in a moment.")
                return@launch
            }

            _uiState.value = RadioUiState.Loading(
                if (cleanQuery == null) "Tuning global frequencies..." else "Shuffling “$cleanQuery” radio..."
            )

            try {
                val station = withContext(Dispatchers.IO) {
                    repository.nextStation(cleanQuery)
                }

                if (station == null) {
                    _uiState.value = RadioUiState.Error(
                        if (cleanQuery == null) {
                            "Could not find a playable station. Try again."
                        } else {
                            "No playable result for “$cleanQuery”. Try a city, country, or station name."
                        }
                    )
                    return@launch
                }

                playStation(station)
            } catch (e: Exception) {
                _uiState.value = RadioUiState.Error("Connection error: ${e.localizedMessage ?: "Unknown"}")
            }
        }
    }

    fun shuffleWorldwide() {
        _activeQuery.value = null
        lastQuery = null
        shuffle(query = null)
    }

    fun clearActiveQuery() {
        _activeQuery.value = null
        lastQuery = null
    }

    fun searchStations(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            _searchResults.value = null
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    repository.searchStations(cleanQuery)
                }
                _searchResults.value = results
            } catch (_: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearchResults() {
        searchJob?.cancel()
        _searchResults.value = null
        _isSearching.value = false
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        _sleepTimerMinutes.value = minutes

        if (minutes == null) return

        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _sleepTimerMinutes.value = null

            val current = _uiState.value
            if (current is RadioUiState.Playing) {
                _uiState.value = current.copy(isPlaying = false, isBuffering = false)
            }
        }
    }

    fun updateFromGithub(context: Context) {
        val appContext = context.applicationContext
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            val updater = AppUpdater(appContext)
            _updateState.value = UpdateUiState.Checking

            try {
                val updateInfo = withContext(Dispatchers.IO) {
                    updater.checkForUpdate()
                }

                if (updateInfo == null) {
                    _updateState.value = UpdateUiState.NoUpdate
                    return@launch
                }

                if (updater.needsInstallPermission()) {
                    _updateState.value = UpdateUiState.NeedsInstallPermission
                    updater.openInstallPermissionSettings()
                    return@launch
                }

                _updateState.value = UpdateUiState.Downloading(updateInfo.versionName, 0, 0L, 0L)
                val apkFile = withContext(Dispatchers.IO) {
                    updater.download(updateInfo) { bytesRead, totalBytes, percent ->
                        _updateState.value = UpdateUiState.Downloading(
                            versionName = updateInfo.versionName,
                            percent = percent,
                            bytesRead = bytesRead,
                            totalBytes = totalBytes
                        )
                    }
                }

                _updateState.value = UpdateUiState.OpeningInstaller(updateInfo.versionName)
                updater.openInstaller(apkFile)
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Error(e.localizedMessage ?: "Update failed")
            }
        }
    }

    fun toggleFavorite() {
        val station = currentStation ?: return
        val newStatus = favoritesManager.toggleFavorite(station)
        _favorites.value = favoritesManager.getFavorites()
        _isCurrentFavorite.value = newStatus
    }

    fun toggleFavoriteStation(station: ResolvedStation) {
        val newStatus = favoritesManager.toggleFavorite(station)
        _favorites.value = favoritesManager.getFavorites()
        if (currentStation?.channelId == station.channelId) {
            _isCurrentFavorite.value = newStatus
        }
    }

    fun removeFavorite(channelId: String) {
        favoritesManager.removeFavorite(channelId)
        _favorites.value = favoritesManager.getFavorites()
        currentStation?.let {
            if (it.channelId == channelId) {
                _isCurrentFavorite.value = false
            }
        }
    }

    fun playSpecificStation(station: ResolvedStation, query: String? = null) {
        SoundFeedback.playShuffleTriggered()
        if (query != null) {
            _activeQuery.value = query.trim().takeIf { it.isNotBlank() }
        }
        shuffleJob?.cancel()
        shuffleJob = viewModelScope.launch {
            if (controller == null) {
                _uiState.value = RadioUiState.Error("Player is still starting. Try again in a moment.")
                return@launch
            }
            _uiState.value = RadioUiState.Loading("Connecting to “${station.title}”...")
            playStation(station)
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        super.onCleared()
    }

    private fun playStation(station: ResolvedStation) {
        currentStation = station
        favoritesManager.addRecent(station)
        _recents.value = favoritesManager.getRecents()
        _isCurrentFavorite.value = favoritesManager.isFavorite(station.channelId)

        val context = getApplication<Application>()
        val artworkBytes = StationArtwork.getArtworkData(context)
        val artworkUri = StationArtwork.getArtworkUri(context)

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(station.title)
            .setDisplayTitle(station.title)
            .setArtist(station.location)
            .setSubtitle(station.location)
            .setDescription(station.channelId)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setArtworkUri(artworkUri)

        if (artworkBytes != null) {
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(station.channelId)
            .setUri(station.streamUrl)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        controller?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }

        _uiState.value = RadioUiState.Playing(
            title = station.title,
            city = station.city,
            country = station.country,
            currentTrack = null,
            isPlaying = true,
            isBuffering = true,
            channelId = station.channelId
        )
    }

    private fun publishPlayingState(
        metadata: MediaMetadata,
        isPlaying: Boolean,
        isBuffering: Boolean
    ) {
        val stationTitle = metadata.title?.toString() ?: "Radio Station"
        val locationParts = (metadata.artist?.toString() ?: "").split(", ")
        val city = locationParts.getOrNull(0).orEmpty()
        val country = locationParts.getOrNull(1).orEmpty()
        val channelId = currentStation?.channelId.orEmpty()

        if (channelId.isNotBlank()) {
            _isCurrentFavorite.value = favoritesManager.isFavorite(channelId)
        }

        _uiState.value = RadioUiState.Playing(
            title = stationTitle,
            city = city,
            country = country,
            currentTrack = null,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            channelId = channelId
        )
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernRadioScreen(viewModel: RadioViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeQuery by viewModel.activeQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isCurrentFavorite.collectAsStateWithLifecycle()
    val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    var currentTab by remember { mutableStateOf(NavTab.RADIO) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val currentChannelId = (state as? RadioUiState.Playing)?.channelId

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C0F14))
            ) {
                // Persistent Mini-Player Bar (visible on Search and Library tabs during playback)
                if (currentTab != NavTab.RADIO && (state is RadioUiState.Playing || state is RadioUiState.Loading)) {
                    MiniPlayerBar(
                        state = state,
                        isFavorite = isFavorite,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onOpenPlayer = { currentTab = NavTab.RADIO }
                    )
                }

                // Modern Bottom Navigation Bar
                BottomNavBar(
                    currentTab = currentTab,
                    onSelectTab = { currentTab = it }
                )
            }
        },
        containerColor = Color(0xFF0C0F14)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                NavTab.RADIO -> {
                    RadioTabContent(
                        state = state,
                        activeQuery = activeQuery,
                        isFavorite = isFavorite,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onShuffle = { query -> viewModel.shuffle(query) },
                        onShuffleWorldwide = { viewModel.shuffleWorldwide() },
                        onOpenSettings = { showSettingsSheet = true }
                    )
                }
                NavTab.SEARCH -> {
                    SearchTabContent(
                        isSearching = isSearching,
                        searchResults = searchResults,
                        currentChannelId = currentChannelId,
                        onSearch = { query -> viewModel.searchStations(query) },
                        onShuffleQuery = { query ->
                            viewModel.shuffle(query)
                            currentTab = NavTab.RADIO
                        },
                        onPlayStation = { station ->
                            viewModel.playSpecificStation(station, query = activeQuery)
                            currentTab = NavTab.RADIO
                        },
                        onClearSearch = { viewModel.clearSearchResults() },
                        onToggleFavorite = { viewModel.toggleFavoriteStation(it) },
                        isFavorite = { channelId -> favorites.any { it.channelId == channelId } }
                    )
                }
                NavTab.LIBRARY -> {
                    LibraryTabContent(
                        favorites = favorites,
                        recents = recents,
                        currentChannelId = currentChannelId,
                        onPlayStation = { station ->
                            viewModel.playSpecificStation(station)
                            currentTab = NavTab.RADIO
                        },
                        onRemoveFavorite = { viewModel.removeFavorite(it.channelId) },
                        onToggleFavorite = { viewModel.toggleFavoriteStation(it) },
                        isFavorite = { channelId -> favorites.any { it.channelId == channelId } }
                    )
                }
            }
        }
    }

    // Hamburger Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsBottomSheet(
            onDismiss = { showSettingsSheet = false },
            sleepTimerMinutes = sleepTimerMinutes,
            onSetSleepTimer = viewModel::setSleepTimer,
            updateState = updateState,
            onCheckUpdate = { viewModel.updateFromGithub(context) }
        )
    }
}

// ==========================================
// 1. Radio Player Tab
// ==========================================
@Composable
private fun RadioTabContent(
    state: RadioUiState,
    activeQuery: String?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onShuffle: (String?) -> Unit,
    onShuffleWorldwide: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val quickGenres = remember {
        listOf("India", "Jazz", "Lo-Fi", "Rock", "Classical", "Electronic", "Ambient", "News")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top App Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_menu),
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Active Query Filter Badge
        if (activeQuery != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF162130), RoundedCornerShape(20.dp))
                    .border(BorderStroke(1.dp, Color(0xFF283B52)), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_location),
                        contentDescription = "Location",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Shuffling: “$activeQuery”",
                        color = Color(0xFF00E676),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onShuffleWorldwide)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Worldwide",
                        color = Color(0xFF8E9BAE),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = "Clear filter",
                        tint = Color(0xFF8E9BAE),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // Center Tuner Card: Compact ~200dp, stable height, no blank empty space
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 200.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
            border = BorderStroke(1.dp, Color(0xFF222B38))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 200.dp)
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                RadioStatus(
                    state = state,
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onTogglePlayPause = onTogglePlayPause
                )
            }
        }

        // Main Action: Big Shuffle Button (shuffles worldwide or within activeQuery)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { onShuffle(activeQuery) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color(0xFF0A120D)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shuffle),
                        contentDescription = "Shuffle",
                        tint = Color(0xFF0A120D),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (activeQuery != null) "Shuffle “$activeQuery” Radio" else "Shuffle Worldwide Radio",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (activeQuery != null) {
                Text(
                    text = "Tap again to find another random station in “$activeQuery”",
                    color = Color(0xFF6E7D91),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Quick Shuffle Moods
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "EXPLORE BY GENRE OR REGION",
                color = Color(0xFF6E7D91),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickGenres) { genre ->
                    val isSelected = activeQuery.equals(genre, ignoreCase = true)
                    OutlinedButton(
                        onClick = { onShuffle(genre) },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) Color(0x2600E676) else Color(0xFF141922),
                            contentColor = if (isSelected) Color(0xFF00E676) else Color.White
                        ),
                        border = BorderStroke(1.dp, if (isSelected) Color(0xFF00E676) else Color(0xFF222B38)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(text = genre, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ==========================================
// 2. Search & Discover Tab (Virtualized LazyColumn)
// ==========================================
@Composable
private fun SearchTabContent(
    isSearching: Boolean,
    searchResults: List<ResolvedStation>?,
    currentChannelId: String?,
    onSearch: (String) -> Unit,
    onShuffleQuery: (String) -> Unit,
    onPlayStation: (ResolvedStation) -> Unit,
    onClearSearch: () -> Unit,
    onToggleFavorite: (ResolvedStation) -> Unit,
    isFavorite: (String) -> Boolean
) {
    var searchText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val suggestions = remember {
        listOf("India", "KIIS FM", "BBC", "Tokyo", "Paris", "New York", "Berlin", "Jazz", "Ibiza", "Lo-Fi")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = "SEARCH WORLDWIDE",
            color = Color(0xFF00E676),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Search Input
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = null,
                    tint = Color(0xFF8E9BAE),
                    modifier = Modifier.size(20.dp)
                )
            },
            label = { Text("Station, city, country, or genre") },
            placeholder = { Text("e.g. India, KIIS FM, Tokyo, jazz, BBC") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    if (searchText.isNotBlank()) onSearch(searchText)
                }
            ),
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = {
                        searchText = ""
                        onClearSearch()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = "Clear search",
                            tint = Color(0xFF8E9BAE),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00E676),
                unfocusedBorderColor = Color(0xFF2F3C4E),
                focusedLabelColor = Color(0xFF00E676),
                unfocusedLabelColor = Color(0xFF8E9BAE),
                focusedPlaceholderColor = Color(0xFF586474),
                unfocusedPlaceholderColor = Color(0xFF586474),
                cursorColor = Color(0xFF00E676)
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onShuffleQuery(searchText)
                },
                enabled = !isSearching,
                modifier = Modifier
                    .weight(1.1f)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color(0xFF0A120D)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shuffle),
                        contentDescription = null,
                        tint = Color(0xFF0A120D),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (searchText.isNotBlank()) "Shuffle “$searchText”" else "Shuffle",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    onSearch(searchText)
                },
                enabled = !isSearching,
                modifier = Modifier
                    .weight(0.9f)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF2F3C4E))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Browse List", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Virtualized Results List (Smooth 60fps, No Lag!)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSearching) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                        border = BorderStroke(1.dp, Color(0xFF222B38))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF00E676)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Searching worldwide stations...",
                                color = Color(0xFF8E9BAE),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else if (searchResults != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RESULTS (${searchResults.size})",
                            color = Color(0xFF8E9BAE),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = "Clear",
                                tint = Color(0xFF6E7D91),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Random Shuffle from Results Banner
                if (searchResults.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onShuffleQuery(searchText) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF15261D)),
                            border = BorderStroke(1.dp, Color(0xFF00E676))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_shuffle),
                                        contentDescription = null,
                                        tint = Color(0xFF00E676),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Random Pick from “${searchText.ifBlank { "Search" }}”",
                                            color = Color(0xFF00E676),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Let the app randomly choose a unique station",
                                            color = Color(0xFF8E9BAE),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_play),
                                        contentDescription = null,
                                        tint = Color(0xFF00E676),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Play", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (searchResults.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                            border = BorderStroke(1.dp, Color(0xFF222B38))
                        ) {
                            Text(
                                text = "No stations found matching this search. Try a country, city, or station name.",
                                color = Color(0xFF6E7D91),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(searchResults, key = { it.channelId }) { station ->
                        StationItemRow(
                            station = station,
                            isCurrent = station.channelId == currentChannelId,
                            isFav = isFavorite(station.channelId),
                            onPlay = { onPlayStation(station) },
                            onToggleFavorite = { onToggleFavorite(station) }
                        )
                    }
                }
            } else {
                // Suggestions when search has not run yet
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "POPULAR DESTINATIONS (TAP TO SHUFFLE)",
                            color = Color(0xFF6E7D91),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(suggestions) { tag ->
                                Card(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            searchText = tag
                                            onShuffleQuery(tag)
                                        },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                                    border = BorderStroke(1.dp, Color(0xFF222B38))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_shuffle),
                                            contentDescription = null,
                                            tint = Color(0xFF00E676),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = tag,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. Library Tab (Favorites & Recents with LazyColumn)
// ==========================================
@Composable
private fun LibraryTabContent(
    favorites: List<ResolvedStation>,
    recents: List<ResolvedStation>,
    currentChannelId: String?,
    onPlayStation: (ResolvedStation) -> Unit,
    onRemoveFavorite: (ResolvedStation) -> Unit,
    onToggleFavorite: (ResolvedStation) -> Unit,
    isFavorite: (String) -> Boolean
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.FAVORITES) }
    val displayRecents = remember(recents) { recents.take(20) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = "YOUR LIBRARY",
            color = Color(0xFF00E676),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Sub-filter tabs (Favorites vs Recents)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141922), RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, Color(0xFF222B38)), RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { selectedTab = LibraryTab.FAVORITES },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == LibraryTab.FAVORITES) Color(0xFF00E676) else Color.Transparent,
                    contentColor = if (selectedTab == LibraryTab.FAVORITES) Color(0xFF0A120D) else Color(0xFF8E9BAE)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_star),
                        contentDescription = null,
                        tint = if (selectedTab == LibraryTab.FAVORITES) Color(0xFF0A120D) else Color(0xFF8E9BAE),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Favorites (${favorites.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = { selectedTab = LibraryTab.RECENTS },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == LibraryTab.RECENTS) Color(0xFF00E676) else Color.Transparent,
                    contentColor = if (selectedTab == LibraryTab.RECENTS) Color(0xFF0A120D) else Color(0xFF8E9BAE)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = null,
                        tint = if (selectedTab == LibraryTab.RECENTS) Color(0xFF0A120D) else Color(0xFF8E9BAE),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Recents (${displayRecents.size}/20)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Virtualized LazyColumn for smooth performance
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedTab == LibraryTab.FAVORITES) {
                if (favorites.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                            border = BorderStroke(1.dp, Color(0xFF222B38))
                        ) {
                            Text(
                                text = "No favorites yet. Tap the heart icon while listening to save stations here!",
                                color = Color(0xFF6E7D91),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            )
                        }
                    }
                } else {
                    items(favorites, key = { it.channelId }) { station ->
                        StationItemRow(
                            station = station,
                            isCurrent = station.channelId == currentChannelId,
                            isFav = true,
                            onPlay = { onPlayStation(station) },
                            onToggleFavorite = { onRemoveFavorite(station) }
                        )
                    }
                }
            } else {
                if (displayRecents.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                            border = BorderStroke(1.dp, Color(0xFF222B38))
                        ) {
                            Text(
                                text = "No recent stations played yet. Start exploring radio to build your history.",
                                color = Color(0xFF6E7D91),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            )
                        }
                    }
                } else {
                    items(displayRecents, key = { it.channelId }) { station ->
                        StationItemRow(
                            station = station,
                            isCurrent = station.channelId == currentChannelId,
                            isFav = isFavorite(station.channelId),
                            onPlay = { onPlayStation(station) },
                            onToggleFavorite = { onToggleFavorite(station) }
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// Reusable Station Item Row (Virtualized)
// ==========================================
@Composable
private fun StationItemRow(
    station: ResolvedStation,
    isCurrent: Boolean,
    isFav: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) Color(0xFF15261D) else Color(0xFF141922)
        ),
        border = BorderStroke(
            1.dp,
            if (isCurrent) Color(0xFF00E676) else Color(0xFF222B38)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(id = if (isCurrent) R.drawable.ic_play else R.drawable.ic_radio),
                    contentDescription = null,
                    tint = if (isCurrent) Color(0xFF00E676) else Color(0xFF8E9BAE),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = station.title,
                        color = if (isCurrent) Color(0xFF00E676) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = station.location,
                        color = Color(0xFF8E9BAE),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border),
                    contentDescription = if (isFav) "Favorited" else "Add to favorites",
                    tint = if (isFav) Color(0xFFFF2D55) else Color(0xFF8E9BAE),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ==========================================
// Persistent Mini-Player Bar
// ==========================================
@Composable
private fun MiniPlayerBar(
    state: RadioUiState,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val isPlaying = (state as? RadioUiState.Playing)?.isPlaying == true
    val isBuffering = (state as? RadioUiState.Playing)?.isBuffering == true || state is RadioUiState.Loading
    val title = when (state) {
        is RadioUiState.Playing -> state.title
        is RadioUiState.Loading -> state.message
        else -> "Radio Shuffler"
    }
    val location = (state as? RadioUiState.Playing)?.let { "${it.city}, ${it.country}".trim(',', ' ') } ?: "Live Stream"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenPlayer),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222E)),
        border = BorderStroke(1.dp, Color(0xFF2E3A4D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isBuffering) Color(0xFFFFC107) else Color(0xFF00E676))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = location,
                        color = Color(0xFF8E9BAE),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border),
                        contentDescription = if (isFavorite) "Favorited" else "Add to favorites",
                        tint = if (isFavorite) Color(0xFFFF2D55) else Color(0xFF8E9BAE),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF00E676)
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// Modern Bottom Navigation Bar
// ==========================================
@Composable
private fun BottomNavBar(
    currentTab: NavTab,
    onSelectTab: (NavTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141922))
            .border(BorderStroke(1.dp, Color(0xFF222B38)))
            .navigationBarsPadding()
            .height(60.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavTab.values().forEach { tab ->
            val selected = currentTab == tab
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = tab.iconRes),
                    contentDescription = tab.title,
                    tint = if (selected) Color(0xFF00E676) else Color(0xFF8E9BAE),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = tab.title,
                    color = if (selected) Color(0xFF00E676) else Color(0xFF8E9BAE),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

// ==========================================
// Settings Bottom Sheet (Hamburger Menu)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    onDismiss: () -> Unit,
    sleepTimerMinutes: Int?,
    onSetSleepTimer: (Int?) -> Unit,
    updateState: UpdateUiState,
    onCheckUpdate: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141922),
        contentColor = Color.White,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_settings),
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings & Controls",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = "Close",
                        tint = Color(0xFF8E9BAE),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Divider
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF222B38)))

            // 1. Sleep Timer
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_timer),
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sleep Timer",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = if (sleepTimerMinutes == null) "Off" else "$sleepTimerMinutes min",
                        color = if (sleepTimerMinutes == null) Color(0xFF8E9BAE) else Color(0xFF00E676),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TimerButton("Off", sleepTimerMinutes == null, Modifier.weight(1f)) { onSetSleepTimer(null) }
                    TimerButton("15m", sleepTimerMinutes == 15, Modifier.weight(1f)) { onSetSleepTimer(15) }
                    TimerButton("30m", sleepTimerMinutes == 30, Modifier.weight(1f)) { onSetSleepTimer(30) }
                    TimerButton("60m", sleepTimerMinutes == 60, Modifier.weight(1f)) { onSetSleepTimer(60) }
                }
            }

            // Divider
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF222B38)))

            // 2. App Updates
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_update),
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "App Updates",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                UpdateControls(
                    updateState = updateState,
                    onUpdate = onCheckUpdate
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

// ==========================================
// Tuner Status Inside Tuner Card
// ==========================================
@Composable
private fun RadioStatus(
    state: RadioUiState,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onTogglePlayPause: () -> Unit
) {
    val playButtonBrush = remember {
        Brush.verticalGradient(
            listOf(Color(0xFF222C3A), Color(0xFF18202B))
        )
    }

    Crossfade(
        targetState = state,
        animationSpec = tween(durationMillis = 200),
        label = "RadioStatusCrossfade"
    ) { current ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 165.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (current) {
                is RadioUiState.Idle -> {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1B222E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_radio),
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Ready to Explore",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap Shuffle to stream from random stations worldwide.",
                        color = Color(0xFF7E8B9B),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                is RadioUiState.Loading -> {
                    CircularProgressIndicator(
                        color = Color(0xFF00E676),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = current.message,
                        color = Color(0xFF8E9BAE),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                is RadioUiState.Playing -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    if (current.isBuffering) Color(0x26FFC107) else Color(0x1A00E676),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (current.isBuffering) Color(0xFFFFC107) else Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    current.isBuffering -> "BUFFERING"
                                    current.isPlaying -> "LIVE STREAM"
                                    else -> "PAUSED"
                                },
                                color = if (current.isBuffering) Color(0xFFFFC107) else Color(0xFF00E676),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border),
                                contentDescription = if (isFavorite) "Favorited" else "Add to favorites",
                                tint = if (isFavorite) Color(0xFFFF2D55) else Color(0xFF8E9BAE),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = current.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_location),
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${current.city}, ${current.country}",
                            color = Color(0xFF00E676),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!current.currentTrack.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2330)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_music_note),
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = current.currentTrack,
                                    color = Color(0xFFE0E6ED),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (current.isBuffering) {
                            CircularProgressIndicator(
                                color = Color(0xFF00E676),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                        } else {
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(playButtonBrush)
                                    .border(1.5.dp, Color(0xFF2F3C4E), CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(id = if (current.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                                    contentDescription = if (current.isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                is RadioUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0x26FF5252)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_warning),
                            contentDescription = null,
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = current.message,
                        color = Color(0xFFFF5252),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ==========================================
// Helper Buttons
// ==========================================
@Composable
private fun TimerButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0x1A00E676) else Color.Transparent,
            contentColor = if (selected) Color(0xFF00E676) else Color(0xFF8E9BAE)
        )
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun UpdateControls(
    updateState: UpdateUiState,
    onUpdate: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onUpdate,
            enabled = updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFF1B2330),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF18202B),
                disabledContentColor = Color.White
            ),
            border = BorderStroke(1.dp, Color(0xFF2C394B))
        ) {
            if (updateState is UpdateUiState.Checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF00E676)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Checking GitHub releases...",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_update),
                    contentDescription = null,
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Check for app update",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }

        when (updateState) {
            UpdateUiState.Idle -> Unit
            UpdateUiState.Checking -> Unit
            is UpdateUiState.Downloading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloading v${updateState.versionName}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (updateState.percent >= 0) "${updateState.percent}%" else "Downloading...",
                            color = Color(0xFF00E676),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (updateState.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { (updateState.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF00E676),
                            trackColor = Color(0xFF222B38)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF00E676),
                            trackColor = Color(0xFF222B38)
                        )
                    }
                    if (updateState.totalBytes > 0) {
                        val currentMb = String.format(java.util.Locale.US, "%.1f", updateState.bytesRead / (1024.0 * 1024.0))
                        val totalMb = String.format(java.util.Locale.US, "%.1f", updateState.totalBytes / (1024.0 * 1024.0))
                        Text(
                            text = "$currentMb MB / $totalMb MB",
                            color = Color(0xFF8E9BAE),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            is UpdateUiState.OpeningInstaller -> {
                Text(
                    text = "Opening installer for ${updateState.versionName}...",
                    color = Color(0xFF00E676),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            UpdateUiState.NoUpdate -> {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16202C)),
                    border = BorderStroke(1.dp, Color(0xFF223041)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_radio),
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "You are already on the latest release.",
                            color = Color(0xFF8E9BAE),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            UpdateUiState.NeedsInstallPermission -> {
                Text(
                    text = "Allow “Install unknown apps” for Radio Shuffler, then tap update again.",
                    color = Color(0xFFFFB300),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is UpdateUiState.Error -> {
                Text(
                    text = updateState.message,
                    color = Color(0xFFFF5252),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
