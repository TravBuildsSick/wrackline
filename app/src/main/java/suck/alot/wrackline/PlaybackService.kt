package suck.alot.wrackline

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground MediaSessionService — this is what keeps audio playing (and the lock-screen
 * transport controls alive) after the screen turns off or MainActivity is backgrounded.
 * Media3 handles the foreground notification, audio focus, and becoming-noisy (headphone
 * unplug) automatically once a MediaSession is attached to a player with audio attributes set.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Taps ExoPlayer's own PCM stream for the visualizer — no android.media.audiofx.Visualizer,
        // no RECORD_AUDIO permission, and it can't fail to attach the way a Visualizer sometimes does.
        val tee = TeeAudioProcessor(VisualizerSink(AudioReactive.analyzer))
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(tee),
                    )
                    .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = pushWidgetUpdate()
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = pushWidgetUpdate()
        })
    }

    private fun pushWidgetUpdate() {
        val metadata = player.currentMediaItem?.mediaMetadata
        WracklineWidgetProvider.updateAll(
            context = this,
            trackName = metadata?.title?.toString() ?: "Wrackline",
            artist = metadata?.artist?.toString(),
            isPlaying = player.isPlaying,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    // Without this, swiping the app away from Recents leaves the service (and its foreground
    // notification) running indefinitely even when nothing is playing — MediaSessionService
    // gets no other signal that the task is gone. Only actually-playing audio should survive
    // the task being swiped away; paused/stopped playback should not.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
