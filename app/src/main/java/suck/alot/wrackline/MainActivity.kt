package suck.alot.wrackline

import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

// Light variants are lighter tints of the same teal/navy hue family, not a different palette.
private val NavyBgDark = Color(0xFF0A0807)
private val NavyBgLight = Color(0xFFEFF3F1)
private val RedAccent = Color(0xFFC2453A)
private val Cream = Color(0xFFCFC1A3)
private val TextPrimaryDark = Color(0xFFEEF7F4)
private val TextPrimaryLight = Color(0xFF12211E)
private val TextSecondaryDark = Color(0xFF90A8A3)
private val TextSecondaryLight = Color(0xFF4F6B65)
private val TextMutedDark = Color(0xFF5F7C76)
private val TextMutedLight = Color(0xFF8AA39D)

private val NavyBg: Color
    @Composable get() = if (androidx.compose.foundation.isSystemInDarkTheme()) NavyBgDark else NavyBgLight
private val TextPrimary: Color
    @Composable get() = if (androidx.compose.foundation.isSystemInDarkTheme()) TextPrimaryDark else TextPrimaryLight
private val TextSecondary: Color
    @Composable get() = if (androidx.compose.foundation.isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
private val TextMuted: Color
    @Composable get() = if (androidx.compose.foundation.isSystemInDarkTheme()) TextMutedDark else TextMutedLight

private val ArtPalette = listOf(
    Brush.linearGradient(listOf(Color(0xFF8A2E2E), Color(0xFFC2453A))),
    Brush.linearGradient(listOf(Color(0xFF2E4A3C), Color(0xFF5C8A6E))),
    Brush.linearGradient(listOf(Color(0xFF3C2E5C), Color(0xFF6E5C9A))),
    Brush.linearGradient(listOf(Color(0xFF5C4A2E), Color(0xFF9A7C4A))),
    Brush.linearGradient(listOf(Color(0xFF2E4A5C), Color(0xFF4A7C9A))),
)

data class Track(
    val id: String,
    val uri: Uri,
    val name: String,
    val artist: String?,
    val artIndex: Int,
)

private enum class RepeatMode { OFF, ALL, ONE }

class MainActivity : ComponentActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        setContent {
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = RedAccent,
                    onPrimary = Color.White,
                    background = NavyBgDark,
                    onBackground = TextPrimaryDark,
                    surface = NavyBgDark,
                    onSurface = TextPrimaryDark,
                )
            } else {
                androidx.compose.material3.lightColorScheme(
                    primary = RedAccent,
                    onPrimary = Color.White,
                    background = NavyBgLight,
                    onBackground = TextPrimaryLight,
                    surface = NavyBgLight,
                    onSurface = TextPrimaryLight,
                )
            }
            MaterialTheme(colorScheme = colorScheme) {
                PlayerScreen(controllerFuture = controllerFuture)
            }
        }
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun PlayerScreen(controllerFuture: ListenableFuture<MediaController>) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity

    var controller by remember { mutableStateOf<MediaController?>(null) }
    val tracks = remember { mutableStateListOf<Track>() }
    val liked = remember { mutableStateOf(setOf<String>()) }
    var currentTrackId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableFloatStateOf(0f) }
    var shuffleOn by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(RepeatMode.OFF) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableFloatStateOf(0f) }

    var libraryPermissionGranted by remember { mutableStateOf(false) }
    val libraryPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var packSyncStatus by remember { mutableStateOf<String?>("Checking for preinstalled music…") }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var selectedTabId by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) {
        playlists = withContext(Dispatchers.IO) { loadPlaylists(activity) }
    }

    fun refreshLibrary() {
        val c = controller ?: return
        val onDevice = if (libraryPermissionGranted) queryLocalTracks(activity) else emptyList()
        val fromPacks = queryPackTracks(activity)
        val found = fromPacks + onDevice
        tracks.clear()
        tracks.addAll(found)
        val items = found.map { track ->
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaId(track.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).setArtist(track.artist).build())
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
    }

    fun playFrom(list: List<Track>, index: Int) {
        val c = controller ?: return
        val items = list.map { track ->
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaId(track.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).setArtist(track.artist).build())
                .build()
        }
        c.setMediaItems(items, index, 0)
        c.prepare()
        c.play()
    }

    // Preinstalled packs (e.g. Shorebreak) download once on first launch, independent of the
    // on-device library permission — they live in app-private storage, no permission needed.
    // Also re-derives each pack's locked playlist on every launch (not just right after a fresh
    // download) so an update from a version that predates playlists still ends up with one.
    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        try {
            val manifest = withContext(Dispatchers.IO) { fetchPackManifest() }
            for (pack in manifest.filter { it.preinstalled }) {
                if (!isPackInstalled(activity, pack.id)) {
                    packSyncStatus = "Downloading ${pack.name}…"
                    withContext(Dispatchers.IO) { downloadAndInstallPack(activity, pack) }
                }
                val packTrackIds = withContext(Dispatchers.IO) {
                    queryTracksForPack(activity, pack.id).map { it.id }
                }
                if (packTrackIds.isNotEmpty()) {
                    playlists = withContext(Dispatchers.IO) {
                        ensureLockedPackPlaylist(activity, pack.name, packTrackIds)
                    }
                }
            }
        } catch (e: Exception) {
            // No network / repo unreachable — fine, just skip preinstall this launch.
        } finally {
            packSyncStatus = null
            refreshLibrary()
        }
    }

    // POST_NOTIFICATIONS matters here specifically for lock-screen playback — without it,
    // Media3's foreground-service notification (which carries the lock-screen transport
    // controls) can't post on API 33+, even though the service itself still runs.
    val requestedPermissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(libraryPermission, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(libraryPermission)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        libraryPermissionGranted = results[libraryPermission] == true
        if (libraryPermissionGranted) refreshLibrary()
        // POST_NOTIFICATIONS being denied doesn't block this — playback still works, just
        // without the lock-screen notification.
    }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(requestedPermissions)
    }

    DisposableEffect(controllerFuture) {
        val listener = Runnable {
            val c = controllerFuture.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentTrackId = mediaItem?.mediaId
                    durationMs = c.duration.coerceAtLeast(0).toFloat()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) durationMs = c.duration.coerceAtLeast(0).toFloat()
                }
            })
        }
        controllerFuture.addListener(listener, androidx.core.content.ContextCompat.getMainExecutor(activity))
        onDispose { }
    }

    // Poll playback position — Media3's listener doesn't push continuous position updates.
    LaunchedEffect(controller, isPlaying) {
        while (true) {
            val c = controller
            if (c != null && !isSeeking) {
                positionMs = c.currentPosition.toFloat()
            }
            delay(250)
        }
    }

    // Covers the case where permission was granted before the MediaController finished
    // connecting (or vice versa) — whichever resolves second triggers the load.
    LaunchedEffect(controller, libraryPermissionGranted) {
        if (controller != null && libraryPermissionGranted && tracks.isEmpty()) refreshLibrary()
    }

    val currentTrack = tracks.find { it.id == currentTrackId }
    var visualizerChoice by remember { mutableStateOf(VisualizerChoice.RAIN) }
    var expanded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var addToPlaylistTrackId by remember { mutableStateOf<String?>(null) }
    val selectedPlaylist = playlists.find { it.id == selectedTabId }
    val displayedTracks = if (selectedTabId == "all") {
        tracks
    } else {
        selectedPlaylist?.trackIds?.mapNotNull { id -> tracks.find { it.id == id } } ?: emptyList()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBg)
            .background(
                Brush.radialGradient(
                    colors = listOf(RedAccent.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(0.25f, 0.1f),
                    radius = 900f,
                ),
            ),
    ) {
        val timeline = @Composable {
            val sliderValue = if (isSeeking) seekPreview else positionMs
            val sliderMax = durationMs.coerceAtLeast(1f)
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                Slider(
                    value = sliderValue.coerceIn(0f, sliderMax),
                    onValueChange = {
                        isSeeking = true
                        seekPreview = it
                    },
                    onValueChangeFinished = {
                        controller?.seekTo(seekPreview.toLong())
                        isSeeking = false
                    },
                    valueRange = 0f..sliderMax,
                    colors = SliderDefaults.colors(
                        thumbColor = RedAccent,
                        activeTrackColor = RedAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime((if (isSeeking) seekPreview else positionMs).toLong()), color = TextSecondary, fontSize = 12.sp)
                    Text(formatTime(durationMs.toLong()), color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        val tabOrder = listOf("all") + playlists.map { it.id }
        var tabDragAccum by remember { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta -> tabDragAccum += delta },
                    onDragStopped = {
                        val currentIdx = tabOrder.indexOf(selectedTabId).coerceAtLeast(0)
                        if (tabDragAccum < -80f && currentIdx < tabOrder.lastIndex) {
                            selectedTabId = tabOrder[currentIdx + 1]
                        } else if (tabDragAccum > 80f && currentIdx > 0) {
                            selectedTabId = tabOrder[currentIdx - 1]
                        }
                        tabDragAccum = 0f
                    },
                ),
        ) {
            VisualizerSquare(
                choice = visualizerChoice,
                onChoiceChange = { visualizerChoice = it },
                trackName = currentTrack?.name ?: "Nothing playing",
                expanded = expanded,
                onExpandChange = { expanded = it },
                timelineContent = timeline,
                onRescan = {
                    if (libraryPermissionGranted) {
                        refreshLibrary()
                    } else {
                        permissionLauncher.launch(requestedPermissions)
                    }
                },
                isPlaying = isPlaying,
                isShuffleOn = shuffleOn,
                repeatMode = repeatMode,
                isLiked = currentTrack?.id in liked.value,
                onShuffle = {
                    shuffleOn = !shuffleOn
                    controller?.shuffleModeEnabled = shuffleOn
                },
                onPrevious = { controller?.seekToPrevious() },
                onPlayPause = {
                    val c = controller ?: return@VisualizerSquare
                    if (c.isPlaying) c.pause() else c.play()
                },
                onNext = { controller?.seekToNext() },
                onRepeat = {
                    repeatMode = when (repeatMode) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                    controller?.repeatMode = when (repeatMode) {
                        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    }
                },
                onLike = {
                    val id = currentTrack?.id ?: return@VisualizerSquare
                    liked.value = if (id in liked.value) liked.value - id else liked.value + id
                },
            )

            if (!expanded) {
                Spacer(Modifier.height(16.dp))
                timeline()

                Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 22.dp)) {
                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabChip(label = "All", selected = selectedTabId == "all", locked = false) { selectedTabId = "all" }
                        playlists.forEach { p ->
                            TabChip(label = p.name, selected = selectedTabId == p.id, locked = p.locked) { selectedTabId = p.id }
                        }
                        TabChip(label = "+ New", selected = false, locked = false) { showCreateDialog = true }
                    }

                    if (selectedPlaylist != null && !selectedPlaylist.locked) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                "Delete playlist",
                                color = TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    playlists = deletePlaylist(activity, selectedPlaylist.id)
                                    selectedTabId = "all"
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    packSyncStatus?.let {
                        Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    if (displayedTracks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when {
                                    packSyncStatus != null -> ""
                                    selectedTabId != "all" -> "No tracks in this playlist yet"
                                    !libraryPermissionGranted -> "Grant music library access to see your songs"
                                    else -> "No music found on this device"
                                },
                                color = TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(displayedTracks, key = { it.id }) { track ->
                                val index = displayedTracks.indexOf(track)
                                QueueRow(
                                    track = track,
                                    isCurrent = track.id == currentTrackId,
                                    isLiked = track.id in liked.value,
                                    showRemove = selectedTabId != "all" && selectedPlaylist?.locked != true,
                                    onPlay = { playFrom(displayedTracks, index) },
                                    onLike = {
                                        liked.value = if (track.id in liked.value) liked.value - track.id else liked.value + track.id
                                    },
                                    onRemove = {
                                        if (selectedPlaylist != null) {
                                            playlists = removeTrackFromPlaylist(activity, selectedPlaylist.id, track.id)
                                        }
                                    },
                                    onAddToPlaylist = { addToPlaylistTrackId = track.id },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreateDialog = false; newPlaylistName = "" },
            title = { Text("New playlist") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        playlists = createPlaylist(activity, newPlaylistName.trim())
                    }
                    showCreateDialog = false
                    newPlaylistName = ""
                }) { Text("Create") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCreateDialog = false; newPlaylistName = "" }) { Text("Cancel") }
            },
        )
    }

    val addToPlaylistTarget = addToPlaylistTrackId
    if (addToPlaylistTarget != null) {
        val userPlaylists = playlists.filter { !it.locked }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { addToPlaylistTrackId = null },
            title = { Text("Add to playlist") },
            text = {
                if (userPlaylists.isEmpty()) {
                    Text("No playlists yet — tap \"+ New\" to make one.", color = TextSecondary)
                } else {
                    Column {
                        userPlaylists.forEach { p ->
                            Text(
                                p.name,
                                color = TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playlists = addTrackToPlaylist(activity, p.id, addToPlaylistTarget)
                                        addToPlaylistTrackId = null
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { addToPlaylistTrackId = null }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) RedAccent else Color.White.copy(alpha = 0.06f))
            .border(
                1.dp,
                if (selected) RedAccent else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(20.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (locked) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = if (selected) Color.White else TextSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private enum class VisualizerChoice { RAIN, STRAND }

@Composable
private fun VisualizerSquare(
    choice: VisualizerChoice,
    onChoiceChange: (VisualizerChoice) -> Unit,
    trackName: String,
    onRescan: () -> Unit,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    timelineContent: @Composable () -> Unit,
    isPlaying: Boolean,
    isShuffleOn: Boolean,
    repeatMode: RepeatMode,
    isLiked: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    onLike: () -> Unit,
) {
    val heightFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 0.92f else 0.5f,
        label = "visualizerHeight",
    )
    var dragAccum by remember { mutableFloatStateOf(0f) }
    // Overlay text/icons sit on top of the visualizer canvas itself, which flips between a
    // near-black and a near-white water in dark/light mode — so these need to track that same
    // switch rather than assuming a dark canvas underneath.
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val onVisualizer = if (isDark) Color.White else Color(0xFF0F3630)
    val glassTint = if (isDark) Color.White else Color(0xFF0F3630)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(heightFraction)
            .draggable(
                orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                state = androidx.compose.foundation.gestures.rememberDraggableState { delta -> dragAccum += delta },
                onDragStopped = {
                    if (dragAccum > 60f) onExpandChange(true) else if (dragAccum < -60f) onExpandChange(false)
                    dragAccum = 0f
                },
            ),
    ) {
        when (choice) {
            VisualizerChoice.RAIN -> RainVisualizer(analyzer = AudioReactive.analyzer, modifier = Modifier.fillMaxSize())
            VisualizerChoice.STRAND -> WracklineVisualizer(analyzer = AudioReactive.analyzer, modifier = Modifier.fillMaxSize())
        }

        // Header + title float directly over the visualizer instead of sitting above it.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 36.dp, start = 22.dp, end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("NOW PLAYING", color = onVisualizer.copy(alpha = 0.7f), fontSize = 12.sp, letterSpacing = 1.sp)
                IconButton(onClick = onRescan) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan library", tint = onVisualizer)
                }
            }
            Text(
                trackName,
                color = onVisualizer,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(VisualizerChoice.RAIN to "Rain", VisualizerChoice.STRAND to "Strand").forEach { (c, label) ->
                    val selected = choice == c
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) glassTint.copy(alpha = 0.22f) else glassTint.copy(alpha = 0.08f))
                            .border(1.dp, glassTint.copy(alpha = if (selected) 0.4f else 0.16f), RoundedCornerShape(14.dp))
                            .clickable { onChoiceChange(c) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(label, color = onVisualizer, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Transport controls — each button is its own floating glass circle, no dock behind
        // them. When expanded, the timeline sits directly under this row instead of below the
        // whole square.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassyIconButton(
                    Icons.Filled.Shuffle,
                    tint = if (isShuffleOn) RedAccent else onVisualizer,
                    glassTint = glassTint,
                    onClick = onShuffle,
                )
                GlassyIconButton(Icons.Filled.SkipPrevious, tint = onVisualizer, glassTint = glassTint, size = 40.dp, onClick = onPrevious)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(RedAccent)
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                GlassyIconButton(Icons.Filled.SkipNext, tint = onVisualizer, glassTint = glassTint, size = 40.dp, onClick = onNext)
                GlassyIconButton(
                    if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    tint = if (repeatMode == RepeatMode.OFF) onVisualizer.copy(alpha = 0.6f) else RedAccent,
                    glassTint = glassTint,
                    onClick = onRepeat,
                )
                GlassyIconButton(
                    if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    tint = RedAccent,
                    glassTint = glassTint,
                    onClick = onLike,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                timelineContent()
            }
        }
    }
}

@Composable
private fun GlassyIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    glassTint: Color = Color.White,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size + 18.dp)
            .clip(CircleShape)
            .background(glassTint.copy(alpha = 0.10f))
            .border(1.dp, glassTint.copy(alpha = 0.22f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isCurrent: Boolean,
    isLiked: Boolean,
    showRemove: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onRemove: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPlay() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ArtPalette[track.artIndex % ArtPalette.size]),
            contentAlignment = Alignment.Center,
        ) {
            Text(track.name.take(1).uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            track.name,
            color = if (isCurrent) RedAccent else TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onLike) {
            Icon(
                if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "Like",
                tint = RedAccent,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(Icons.Filled.Add, contentDescription = "Add to playlist", tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        if (showRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove from playlist", tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}
