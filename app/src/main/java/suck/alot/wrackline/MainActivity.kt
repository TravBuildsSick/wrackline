package suck.alot.wrackline

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val NavyBg = Color(0xFF0A0807)
private val RedAccent = Color(0xFFC2453A)
private val Cream = Color(0xFFCFC1A3)
private val TextPrimary = Color(0xFFEEF7F4)
private val TextSecondary = Color(0xFF90A8A3)
private val TextMuted = Color(0xFF5F7C76)

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
            val colorScheme = darkColorScheme(
                primary = RedAccent,
                onPrimary = Color.White,
                background = NavyBg,
                onBackground = TextPrimary,
                surface = NavyBg,
                onSurface = TextPrimary,
            )
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
    var currentIndex by remember { mutableIntStateOf(-1) }
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

    fun loadLibrary() {
        val c = controller ?: return
        val found = queryLocalTracks(activity)
        tracks.clear()
        tracks.addAll(found)
        val items = found.map { track ->
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaId(track.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).build())
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
    }

    // POST_NOTIFICATIONS matters here specifically for lock-screen playback — without it,
    // Media3's foreground-service notification (which carries the lock-screen transport
    // controls) can't post on API 33+, even though the service itself still runs.
    val requestedPermissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(libraryPermission, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(libraryPermission, Manifest.permission.RECORD_AUDIO)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        libraryPermissionGranted = results[libraryPermission] == true
        if (libraryPermissionGranted) loadLibrary()
        // RECORD_AUDIO/POST_NOTIFICATIONS being denied doesn't block this — playback still works,
        // just without the reactive ring and/or the lock-screen notification respectively.
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
                    currentIndex = c.currentMediaItemIndex
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
        if (controller != null && libraryPermissionGranted && tracks.isEmpty()) loadLibrary()
    }

    val currentTrack = tracks.getOrNull(currentIndex)
    val level by AudioReactive.level.collectAsState()

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "NOW PLAYING",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                )
                IconButton(onClick = {
                    if (libraryPermissionGranted) {
                        loadLibrary()
                    } else {
                        permissionLauncher.launch(requestedPermissions)
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan library", tint = TextPrimary)
                }
            }

            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                VinylRing(
                    track = currentTrack,
                    level = if (isPlaying) level else 0f,
                )
            }

            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    currentTrack?.name ?: "Nothing playing",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text("Local file", color = TextSecondary, fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))

            val sliderValue = if (isSeeking) seekPreview else positionMs
            val sliderMax = durationMs.coerceAtLeast(1f)
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

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    shuffleOn = !shuffleOn
                    controller?.shuffleModeEnabled = shuffleOn
                }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleOn) RedAccent else TextSecondary,
                    )
                }
                IconButton(onClick = { controller?.seekToPrevious() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = TextPrimary, modifier = Modifier.size(30.dp))
                }
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(RedAccent)
                        .clickable {
                            val c = controller ?: return@clickable
                            if (c.isPlaying) c.pause() else c.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = NavyBg,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { controller?.seekToNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = TextPrimary, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = {
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
                }) {
                    Icon(
                        if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeatMode == RepeatMode.OFF) TextSecondary else RedAccent,
                    )
                }
                IconButton(onClick = {
                    val id = currentTrack?.id ?: return@IconButton
                    liked.value = if (id in liked.value) liked.value - id else liked.value + id
                }) {
                    val isLiked = currentTrack?.id in liked.value
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Like",
                        tint = RedAccent,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "QUEUE",
                color = TextSecondary,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(8.dp))

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (!libraryPermissionGranted) "Grant music library access to see your songs"
                        else "No music found on this device",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(tracks, key = { it.id }) { track ->
                        val index = tracks.indexOf(track)
                        QueueRow(
                            track = track,
                            isCurrent = index == currentIndex,
                            isLiked = track.id in liked.value,
                            onPlay = {
                                controller?.seekTo(index, 0)
                                controller?.play()
                            },
                            onLike = {
                                liked.value = if (track.id in liked.value) liked.value - track.id else liked.value + track.id
                            },
                            onRemove = {
                                controller?.removeMediaItem(index)
                                tracks.removeAt(index)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VinylRing(track: Track?, level: Float) {
    val glow = 0.35f + level * 0.5f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val ringStroke = 14.dp.toPx()
            drawCircle(
                color = Cream,
                radius = size.minDimension / 2 - ringStroke / 2,
                style = Stroke(width = ringStroke),
            )
            // Grooves — faint radial ticks, cheap stand-in for the mockup's conic-gradient ring.
            val tickCount = 48
            val outerR = size.minDimension / 2 - 2.dp.toPx()
            val innerR = outerR - ringStroke + 2.dp.toPx()
            for (i in 0 until tickCount) {
                val angle = (i / tickCount.toFloat()) * 2 * Math.PI
                val x1 = center.x + (innerR * cos(angle)).toFloat()
                val y1 = center.y + (innerR * sin(angle)).toFloat()
                val x2 = center.x + (outerR * cos(angle)).toFloat()
                val y2 = center.y + (outerR * sin(angle)).toFloat()
                drawLine(
                    color = Color(0xFF4A3F2E).copy(alpha = 0.5f),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 1f,
                )
            }
            // Audio-reactive glow ring — radius/alpha tracks live playback amplitude.
            drawCircle(
                color = RedAccent.copy(alpha = glow * 0.4f),
                radius = size.minDimension / 2 + level * 18.dp.toPx(),
                style = Stroke(width = (4 + level * 10).dp.toPx()),
            )
        }
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    if (track != null) ArtPalette[track.artIndex % ArtPalette.size]
                    else Brush.linearGradient(listOf(Color(0xFF111F1E), Color(0xFF111F1E))),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = track?.name?.take(1)?.uppercase() ?: "",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 48.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isCurrent: Boolean,
    isLiked: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onRemove: () -> Unit,
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
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}
