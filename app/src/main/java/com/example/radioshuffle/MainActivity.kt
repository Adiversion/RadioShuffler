package com.example.radioshuffle

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
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

sealed class RadioUiState {
    object Idle : RadioUiState()
    data class Loading(val message: String = "Tuning global frequencies...") : RadioUiState()
    data class Playing(
        val title: String,
        val city: String,
        val country: String,
        val currentTrack: String?,
        val isPlaying: Boolean,
        val isBuffering: Boolean = false
    ) : RadioUiState()
    data class Error(val message: String) : RadioUiState()
}

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class Downloading(val versionName: String) : UpdateUiState()
    data class OpeningInstaller(val versionName: String) : UpdateUiState()
    object NoUpdate : UpdateUiState()
    object NeedsInstallPermission : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

class RadioViewModel : ViewModel() {
    private val repository = RadioGardenRepository()
    private var controller: MediaController? = null
    private var shuffleJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var updateJob: Job? = null
    private var lastQuery: String? = null

    private val _uiState = MutableStateFlow<RadioUiState>(RadioUiState.Idle)
    val uiState: StateFlow<RadioUiState> = _uiState

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
            mediaItem?.mediaMetadata?.let { meta ->
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
        val cleanQuery = query?.trim()?.takeIf { it.isNotBlank() }
        if (!automatic) {
            lastQuery = cleanQuery
            shuffleJob?.cancel()
        }

        shuffleJob = viewModelScope.launch {
            if (controller == null) {
                _uiState.value = RadioUiState.Error("Player is still starting. Try again in a moment.")
                return@launch
            }

            _uiState.value = RadioUiState.Loading(
                if (cleanQuery == null) "Tuning global frequencies..." else "Searching “$cleanQuery”..."
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

                _updateState.value = UpdateUiState.Downloading(updateInfo.versionName)
                val apkFile = withContext(Dispatchers.IO) {
                    updater.download(updateInfo)
                }

                _updateState.value = UpdateUiState.OpeningInstaller(updateInfo.versionName)
                updater.openInstaller(apkFile)
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Error(e.localizedMessage ?: "Update failed")
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        super.onCleared()
    }

    private fun playStation(station: ResolvedStation) {
        val metadata = MediaMetadata.Builder()
            .setTitle(station.title)
            .setArtist(station.location)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(station.streamUrl)
            .setMediaMetadata(metadata)
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
            isBuffering = true
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

        _uiState.value = RadioUiState.Playing(
            title = stationTitle,
            city = city,
            country = country,
            currentTrack = null,
            isPlaying = isPlaying,
            isBuffering = isBuffering
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

@Composable
fun ModernRadioScreen(viewModel: RadioViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val isLoading = state is RadioUiState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
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
            modifier = Modifier.fillMaxWidth(),
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
                RadioStatus(
                    state = state,
                    onTogglePlayPause = viewModel::togglePlayPause
                )
            }
        }

        SearchSection(
            isLoading = isLoading,
            onSearch = { query -> viewModel.shuffle(query) },
            onShuffle = { viewModel.shuffle() }
        )

        SleepTimerControls(
            selectedMinutes = sleepTimerMinutes,
            onSelect = viewModel::setSleepTimer
        )

        UpdateControls(
            updateState = updateState,
            onUpdate = { viewModel.updateFromGithub(context) }
        )
    }
}

@Composable
private fun SearchSection(
    isLoading: Boolean,
    onSearch: (String) -> Unit,
    onShuffle: () -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search station, city, or country") },
            placeholder = { Text("e.g. Tokyo, BBC, jazz, India") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    if (searchText.isNotBlank()) {
                        onSearch(searchText)
                    }
                }
            ),
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { searchText = "" }) {
                        Text(
                            text = "✕",
                            color = Color(0xFF8E9BAE),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    onSearch(searchText)
                },
                enabled = !isLoading,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Search")
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onShuffle()
                },
                enabled = !isLoading,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color(0xFF0A120D)
                )
            ) {
                Text("🎲 Shuffle", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RadioStatus(
    state: RadioUiState,
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
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (current) {
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
                        text = "Shuffle globally, search by place or station, or use Bluetooth next/previous controls.",
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
                        text = current.message,
                        color = Color(0xFF8E9BAE),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                is RadioUiState.Playing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                if (current.isBuffering) Color(0x26FFC107) else Color(0x1A00E676),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
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
                                current.isBuffering -> "BUFFERING..."
                                current.isPlaying -> "LIVE STREAM"
                                else -> "PAUSED"
                            },
                            color = if (current.isBuffering) Color(0xFFFFC107) else Color(0xFF00E676),
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

                    Box(
                        modifier = Modifier.size(72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (current.isBuffering) {
                            CircularProgressIndicator(
                                color = Color(0xFF00E676),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(54.dp)
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
                                Text(
                                    text = if (current.isPlaying) "❚❚" else "▶",
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
}

@Composable
private fun SleepTimerControls(
    selectedMinutes: Int?,
    onSelect: (Int?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (selectedMinutes == null) "Sleep timer: Off" else "Sleep timer: $selectedMinutes min",
            color = Color(0xFF8E9BAE),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimerButton("Off", selectedMinutes == null, Modifier.weight(1f)) { onSelect(null) }
            TimerButton("15", selectedMinutes == 15, Modifier.weight(1f)) { onSelect(15) }
            TimerButton("30", selectedMinutes == 30, Modifier.weight(1f)) { onSelect(30) }
            TimerButton("60", selectedMinutes == 60, Modifier.weight(1f)) { onSelect(60) }
        }
    }
}

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
        Text(label)
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp)
        ) {
            Text("Check for app update")
        }

        val message = when (updateState) {
            UpdateUiState.Idle -> null
            UpdateUiState.Checking -> "Checking GitHub releases..."
            is UpdateUiState.Downloading -> "Downloading ${updateState.versionName}..."
            is UpdateUiState.OpeningInstaller -> "Opening installer for ${updateState.versionName}..."
            UpdateUiState.NoUpdate -> "You are already on the latest release."
            UpdateUiState.NeedsInstallPermission -> "Allow “Install unknown apps” for Radio Shuffler, then tap update again."
            is UpdateUiState.Error -> updateState.message
        }

        if (message != null) {
            Text(
                text = message,
                color = if (updateState is UpdateUiState.Error) Color(0xFFFF5252) else Color(0xFF8E9BAE),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
