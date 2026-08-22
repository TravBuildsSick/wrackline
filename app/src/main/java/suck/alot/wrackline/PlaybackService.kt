package suck.alot.wrackline

import android.media.audiofx.Visualizer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.abs

/**
 * Foreground MediaSessionService — this is what keeps audio playing (and the lock-screen
 * transport controls alive) after the screen turns off or MainActivity is backgrounded.
 * Media3 handles the foreground notification, audio focus, and becoming-noisy (headphone
 * unplug) automatically once a MediaSession is attached to a player with audio attributes set.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var visualizer: Visualizer? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
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

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachVisualizer(audioSessionId)
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun attachVisualizer(audioSessionId: Int) {
        visualizer?.release()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            AudioReactive.level.value = 0f
            return
        }
        visualizer = try {
            Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            if (waveform == null) return
                            // Waveform bytes are signed 8-bit PCM centered at 128; average
                            // absolute deviation from center is a cheap, stable amplitude proxy.
                            var sum = 0
                            for (b in waveform) sum += abs(b.toInt())
                            val avg = sum.toFloat() / waveform.size
                            AudioReactive.level.value = (avg / 128f).coerceIn(0f, 1f)
                        }

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    /* waveform= */ true,
                    /* fft= */ false,
                )
                enabled = true
            }
        } catch (e: RuntimeException) {
            // Visualizer can fail to init (no RECORD_AUDIO permission, or platform quirks) —
            // the app should keep playing audio either way, just without the reactive ring.
            null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        visualizer?.release()
        visualizer = null
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
