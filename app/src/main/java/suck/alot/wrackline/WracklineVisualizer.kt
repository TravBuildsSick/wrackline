package suck.alot.wrackline

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// Brand ramp, shared with the logo build script.
private val SkyStops = listOf(
    0.00f to Color(0xFF0B1B3A),
    0.10f to Color(0xFF7C2622),
    0.38f to Color(0xFFCE4E1C),
    0.70f to Color(0xFFED8B1F),
    1.00f to Color(0xFFF4C63D),
)
private val Ink = Color(0xFF093C39)

/** A single piece of debris hanging off the strand and slipping away. */
internal class Debris(
    val vertex: Int,
    var life: Float,
    val length: Float,
    val lean: Float,
    val drift: Float,
)

/**
 * Everything the visualiser animates. Held outside composition and mutated in
 * place — allocating per frame at 60fps is what makes Canvas visualisers stutter.
 */
class WracklineVisualizerState(private val bandCount: Int) {

    /** Smoothed band heights, 0..1, one per strand vertex. */
    val vertices = FloatArray(bandCount)

    /** Smoothed loudness driving the strand's baseline height. */
    var tide = 0f
        private set

    /** Slow-decaying high-water mark left behind by the loudest passage. */
    var highWater = 0f
        private set

    internal val debris = mutableListOf<Debris>()

    // Ring of past vertex snapshots so the backwash strand can lag the live one.
    private val historyLength = 24
    private val history = Array(historyLength) { FloatArray(bandCount) }
    private var historyWrite = 0
    private var historyFilled = 0

    private var debrisCooldown = 0f
    private val random = Random(20260822)

    /** Vertex heights as they were roughly [backwashLagFrames] frames ago. */
    fun backwash(out: FloatArray, lagFrames: Int) {
        if (historyFilled == 0) {
            out.fill(0f)
            return
        }
        val lag = min(lagFrames, historyFilled - 1)
        val idx = ((historyWrite - 1 - lag) % historyLength + historyLength) % historyLength
        history[idx].copyInto(out)
    }

    fun update(frame: AudioFrame, dt: Float) {
        // Fast attack, slow release. Symmetric smoothing makes the strand feel
        // rubbery and lag the beat; asymmetric snaps to hits and eases back.
        for (i in vertices.indices) {
            val target = frame.bands.getOrElse(i) { 0f }
            val tau = if (target > vertices[i]) ATTACK_TAU else RELEASE_TAU
            vertices[i] += (target - vertices[i]) * coefficient(dt, tau)
        }

        tide += (frame.level - tide) * coefficient(dt, TIDE_TAU)
        highWater = max(highWater - HIGH_WATER_FALL * dt, tide)

        debrisCooldown -= dt
        if (frame.onset > ONSET_THRESHOLD && debrisCooldown <= 0f) {
            debrisCooldown = DEBRIS_COOLDOWN
            // Bias spawns toward whichever band is loudest, so kicks drop debris
            // at the low end and snares nearer the middle.
            val v = loudestVertex()
            debris += Debris(
                vertex = v,
                life = 1f,
                length = 0.05f + 0.09f * frame.onset,
                lean = (random.nextFloat() - 0.5f) * 0.5f,
                drift = 0.10f + 0.20f * random.nextFloat(),
            )
        }
        val iterator = debris.iterator()
        while (iterator.hasNext()) {
            val d = iterator.next()
            d.life -= dt / DEBRIS_LIFETIME
            if (d.life <= 0f) iterator.remove()
        }

        vertices.copyInto(history[historyWrite])
        historyWrite = (historyWrite + 1) % historyLength
        if (historyFilled < historyLength) historyFilled++
    }

    private fun loudestVertex(): Int {
        var best = 0
        for (i in vertices.indices) if (vertices[i] > vertices[best]) best = i
        return best
    }

    /** Frame-rate independent smoothing: reach 63% of the target in [tau] seconds. */
    private fun coefficient(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

    private companion object {
        const val ATTACK_TAU = 0.020f
        const val RELEASE_TAU = 0.180f
        const val TIDE_TAU = 0.320f
        const val HIGH_WATER_FALL = 0.10f
        const val ONSET_THRESHOLD = 0.35f
        const val DEBRIS_COOLDOWN = 0.09f
        const val DEBRIS_LIFETIME = 1.6f
    }
}

@Composable
fun WracklineVisualizer(
    analyzer: SpectrumAnalyzer,
    modifier: Modifier = Modifier,
) {
    val state = remember(analyzer) { WracklineVisualizerState(analyzer.bandCount) }
    val wash = remember(analyzer) { FloatArray(analyzer.bandCount) }
    var tick by remember { mutableStateOf(0L) }

    LaunchedEffect(analyzer) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                last = now
                state.update(analyzer.latest(), dt)
                tick = now
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick // read so the draw re-runs each frame

        drawRect(
            Brush.verticalGradient(
                colorStops = SkyStops.toTypedArray(),
                startY = 0f,
                endY = size.height,
            )
        )

        // Baseline rides up the frame as the mix gets louder.
        val baseline = size.height * (BASELINE_REST - BASELINE_TRAVEL * state.tide)
        val amplitude = size.height * STRAND_AMPLITUDE

        // High-water mark: where the tide last peaked, fading down.
        val highY = size.height * (BASELINE_REST - BASELINE_TRAVEL * state.highWater)
        drawLine(
            color = Ink.copy(alpha = 0.22f),
            start = Offset(0f, highY),
            end = Offset(size.width, highY),
            strokeWidth = 1.5f,
        )

        // Backwash: the strand as it was a few frames ago, trailing behind.
        state.backwash(wash, BACKWASH_LAG)
        drawStrand(
            heights = wash,
            baseline = baseline + size.height * BACKWASH_DROP,
            amplitude = amplitude * 0.8f,
            color = Ink.copy(alpha = 0.30f),
            width = 4f,
        )

        drawStrand(
            heights = state.vertices,
            baseline = baseline,
            amplitude = amplitude,
            color = Ink,
            width = 7f,
        )

        drawDebris(state, baseline, amplitude)
    }
}

/** The zigzag itself: one vertex per band, height driven by that band's energy. */
private fun DrawScope.drawStrand(
    heights: FloatArray,
    baseline: Float,
    amplitude: Float,
    color: Color,
    width: Float,
) {
    if (heights.isEmpty()) return
    val step = size.width / (heights.size - 1).coerceAtLeast(1)
    val path = Path()
    for (i in heights.indices) {
        // Alternating sign keeps the logo's zigzag silhouette at rest instead of
        // collapsing into a flat line when the track is quiet.
        val sign = if (i % 2 == 0) -1f else 1f
        val y = baseline + sign * amplitude * (REST_ZIGZAG + heights[i])
        val x = i * step
        if (i == 0) path.moveTo(x, baseline) else path.lineTo(x, y)
    }
    path.lineTo(size.width, baseline)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Kelp hanging off the low points, spawned on transients, slipping downward. */
private fun DrawScope.drawDebris(
    state: WracklineVisualizerState,
    baseline: Float,
    amplitude: Float,
) {
    val count = state.vertices.size
    if (count == 0) return
    val step = size.width / (count - 1).coerceAtLeast(1)

    for (d in state.debris) {
        val sign = if (d.vertex % 2 == 0) -1f else 1f
        val anchorY = baseline + sign * amplitude * (REST_ZIGZAG + state.vertices[d.vertex])
        val fallen = (1f - d.life) * d.drift * size.height
        val top = anchorY + fallen
        val len = d.length * size.height * d.life
        val x = d.vertex * step

        drawLine(
            color = Ink.copy(alpha = 0.75f * d.life),
            start = Offset(x, top),
            end = Offset(x + d.lean * len, top + len),
            strokeWidth = 3.5f,
            cap = StrokeCap.Round,
        )
    }
}

private const val BASELINE_REST = 0.62f
private const val BASELINE_TRAVEL = 0.22f
private const val STRAND_AMPLITUDE = 0.11f
private const val REST_ZIGZAG = 0.22f
private const val BACKWASH_LAG = 9
private const val BACKWASH_DROP = 0.035f
