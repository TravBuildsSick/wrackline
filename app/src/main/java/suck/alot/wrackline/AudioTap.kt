package suck.alot.wrackline

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One analysed slice of audio. Immutable so the audio thread can hand it to the
 * UI thread through an AtomicReference without locking.
 *
 * bands  normalised 0..1, low frequency first
 * level  smoothed broadband loudness, 0..1 — drives tide height
 * onset  transient strength, spikes toward 1 on a hit and decays — drives debris
 */
class AudioFrame(
    val bands: FloatArray,
    val level: Float,
    val onset: Float,
) {
    companion object {
        fun silent(bandCount: Int) = AudioFrame(FloatArray(bandCount), 0f, 0f)
    }
}

/** In-place iterative radix-2 FFT. Size must be a power of two. */
internal object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Accumulates PCM from the audio thread, runs an FFT every hop, and publishes an
 * AudioFrame. Per-band adaptive gain means quiet mixes still fill the strand
 * instead of flatlining — a fixed scale looks broken on anything but a loud master.
 */
class SpectrumAnalyzer(
    private val fftSize: Int = 1024,
    val bandCount: Int = 20,
) {
    private val hop = fftSize / 2

    private val ring = FloatArray(fftSize)
    private var writeIndex = 0
    private var sinceLastHop = 0

    private val window = FloatArray(fftSize) {
        0.5f - 0.5f * cos(2.0 * PI * it / (fftSize - 1)).toFloat()
    }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mag = FloatArray(fftSize / 2)
    private val prevMag = FloatArray(fftSize / 2)

    private val bands = FloatArray(bandCount)
    private val bandPeak = FloatArray(bandCount) { MIN_PEAK }
    private var bandEdges = IntArray(bandCount + 1)

    private var sampleRate = 44100
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT

    private var level = 0f
    private var fluxAverage = 0f
    private var onset = 0f

    private val published = AtomicReference(AudioFrame.silent(bandCount))

    /** Latest analysed frame. Poll this once per display frame from the UI. */
    fun latest(): AudioFrame = published.get()

    fun configure(sampleRateHz: Int, channels: Int, pcmEncoding: Int) {
        sampleRate = sampleRateHz
        channelCount = max(1, channels)
        encoding = pcmEncoding
        computeBandEdges()
        reset()
    }

    private fun reset() {
        ring.fill(0f)
        prevMag.fill(0f)
        bands.fill(0f)
        bandPeak.fill(MIN_PEAK)
        writeIndex = 0
        sinceLastHop = 0
        level = 0f
        fluxAverage = 0f
        onset = 0f
        published.set(AudioFrame.silent(bandCount))
    }

    /**
     * Log-spaced band edges. Linear bins put almost everything in the top few
     * bands and the strand only moves on cymbals — log spacing matches how the
     * mix is actually distributed.
     */
    private fun computeBandEdges() {
        val bins = fftSize / 2
        val binHz = sampleRate.toFloat() / fftSize
        val lo = ln(LOW_HZ)
        val hi = ln(min(HIGH_HZ, sampleRate / 2f * 0.95f))
        bandEdges = IntArray(bandCount + 1) { i ->
            val hz = exp(lo + (hi - lo) * i / bandCount)
            (hz / binHz).toInt().coerceIn(1, bins - 1)
        }
        for (i in 1..bandCount) {
            if (bandEdges[i] <= bandEdges[i - 1]) bandEdges[i] = bandEdges[i - 1] + 1
        }
    }

    /** Called on the audio thread. Downmixes to mono and feeds the ring buffer. */
    fun pushPcm(source: ByteBuffer) {
        val buf = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val shorts = buf.asShortBuffer()
                val frames = shorts.remaining() / channelCount
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channelCount) sum += shorts.get() / 32768f
                    accept(sum / channelCount)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floats = buf.asFloatBuffer()
                val frames = floats.remaining() / channelCount
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channelCount) sum += floats.get()
                    accept(sum / channelCount)
                }
            }
        }
    }

    private fun accept(sample: Float) {
        ring[writeIndex] = sample
        writeIndex = (writeIndex + 1) % fftSize
        if (++sinceLastHop >= hop) {
            sinceLastHop = 0
            analyse()
        }
    }

    private fun analyse() {
        var rms = 0f
        for (i in 0 until fftSize) {
            val s = ring[(writeIndex + i) % fftSize]
            rms += s * s
            re[i] = s * window[i]
            im[i] = 0f
        }
        rms = sqrt(rms / fftSize)

        Fft.transform(re, im)

        var flux = 0f
        for (i in mag.indices) {
            val m = sqrt(re[i] * re[i] + im[i] * im[i])
            val d = m - prevMag[i]
            if (d > 0f) flux += d
            prevMag[i] = m
            mag[i] = m
        }

        // Spectral flux against its own running mean. Comparing to an absolute
        // threshold fires constantly on dense mixes and never on sparse ones.
        fluxAverage += (flux - fluxAverage) * FLUX_SMOOTHING
        val excess = (flux - fluxAverage * ONSET_SENSITIVITY) / (fluxAverage + 1e-6f)
        val hit = excess.coerceIn(0f, 1f)
        onset = max(onset * ONSET_DECAY, hit)

        for (b in 0 until bandCount) {
            var sum = 0f
            var count = 0
            for (i in bandEdges[b] until bandEdges[b + 1]) {
                sum += mag[i]
                count++
            }
            val raw = if (count > 0) sum / count else 0f
            bandPeak[b] = max(raw, bandPeak[b] * PEAK_DECAY).coerceAtLeast(MIN_PEAK)
            bands[b] = (raw / bandPeak[b]).coerceIn(0f, 1f)
        }

        val target = (rms * LEVEL_GAIN).coerceIn(0f, 1f)
        level += (target - level) * if (target > level) LEVEL_ATTACK else LEVEL_RELEASE

        published.set(AudioFrame(bands.copyOf(), level, onset))
    }

    private companion object {
        const val LOW_HZ = 45f
        const val HIGH_HZ = 15000f
        const val MIN_PEAK = 1e-4f
        const val PEAK_DECAY = 0.9985f
        const val FLUX_SMOOTHING = 0.08f
        const val ONSET_SENSITIVITY = 1.45f
        const val ONSET_DECAY = 0.72f
        const val LEVEL_GAIN = 3.2f
        const val LEVEL_ATTACK = 0.35f
        const val LEVEL_RELEASE = 0.06f
    }
}

/**
 * Bridges Media3's PCM tap to the analyser. Wire it into the player with:
 *
 *   val analyzer = SpectrumAnalyzer()
 *   val tee = TeeAudioProcessor(VisualizerSink(analyzer))
 *   val renderers = object : DefaultRenderersFactory(context) {
 *       override fun buildAudioSink(...) = DefaultAudioSink.Builder(context)
 *           .setAudioProcessorChain(DefaultAudioProcessorChain(tee))
 *           .build()
 *   }
 *   ExoPlayer.Builder(context, renderers).build()
 */
@OptIn(UnstableApi::class)
class VisualizerSink(
    private val analyzer: SpectrumAnalyzer,
) : TeeAudioProcessor.AudioBufferSink {

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        analyzer.configure(sampleRateHz, channelCount, encoding)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        analyzer.pushPcm(buffer)
    }
}
