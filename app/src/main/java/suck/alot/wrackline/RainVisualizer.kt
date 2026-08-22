package suck.alot.wrackline

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

private class RainPalette(
    val waterStops: Array<Pair<Float, Color>>,
    val ghost: Color,
    val ghostGlow: Color,
)

// True black water — only the faintest lift near the bottom edge, so the glow
// reads against near-pure black rather than a teal pond.
private val DarkPalette = RainPalette(
    waterStops = arrayOf(
        0.00f to Color(0xFF000000),
        0.55f to Color(0xFF010604),
        0.85f to Color(0xFF02120E),
        1.00f to Color(0xFF041F17),
    ),
    ghost = Color(0xFFF2FFFA),
    ghostGlow = Color(0xFF5CFFDA),
)

// Light mode keeps the same "rain on water" composition and the same teal hue
// family as dark mode — just lighter tints throughout, not a different palette.
private val LightPalette = RainPalette(
    waterStops = arrayOf(
        0.00f to Color(0xFFF7FBFA),
        0.55f to Color(0xFFECF5F2),
        0.85f to Color(0xFFDCEEE7),
        1.00f to Color(0xFFC3E3D8),
    ),
    ghost = Color(0xFF0F3630),
    ghostGlow = Color(0xFF17957D),
)

private const val MAX_DROPS = 140
private const val MAX_RIPPLES = 56

/**
 * Rain falling onto a water plane seen at a shallow angle.
 *
 * All geometry is kept in normalised 0..1 space and scaled at draw time, so the
 * same state renders correctly at any size and survives rotation without
 * respawning the scene.
 *
 * Depth (0 = far, at the horizon; 1 = near, at the bottom edge) drives fall
 * speed, streak length, ripple size and brightness together. That single value
 * is what sells the plane as receding — without it the rain reads as a flat
 * field of falling lines.
 */
class RainVisualizerState {

    private class Drop {
        var active = false
        var x = 0f
        var y = 0f
        var depth = 0f
        var landY = 0f
        var speed = 0f
    }

    private class Ripple {
        var active = false
        var x = 0f
        var y = 0f
        var depth = 0f
        var age = 0f
        var life = 1f
        var strength = 0f
    }

    private val drops = Array(MAX_DROPS) { Drop() }
    private val ripples = Array(MAX_RIPPLES) { Ripple() }
    private val random = Random(20260822)

    /** Smoothed loudness. Raises the horizon and brightens the whole scene. */
    var tide = 0f
        private set

    /** Smoothed high-band energy. Drives how hard it's raining. */
    var shimmer = 0f
        private set

    private var spawnCredit = 0f

    fun update(frame: AudioFrame, dt: Float) {
        val bands = frame.bands
        val treble = if (bands.isEmpty()) 0f else {
            var sum = 0f
            val from = bands.size * 2 / 3
            for (i in from until bands.size) sum += bands[i]
            sum / (bands.size - from)
        }

        tide += (frame.level - tide) * coefficient(dt, TIDE_TAU)
        shimmer += (treble - shimmer) * coefficient(dt, SHIMMER_TAU)

        // Rain density tracks the top of the spectrum; a transient dumps a burst
        // all at once so hits read as a spatter rather than a rate change.
        spawnCredit += dt * (BASE_RATE + RATE_RANGE * shimmer)
        if (frame.onset > ONSET_THRESHOLD) spawnCredit += BURST_DROPS * frame.onset
        while (spawnCredit >= 1f) {
            spawnCredit -= 1f
            spawnDrop()
        }

        val horizon = horizonY()
        for (d in drops) {
            if (!d.active) continue
            d.y += d.speed * dt
            if (d.y >= d.landY) {
                d.active = false
                spawnRipple(d.x, d.landY, d.depth)
            }
        }

        for (r in ripples) {
            if (!r.active) continue
            r.age += dt
            if (r.age >= r.life) r.active = false
        }
    }

    private fun spawnDrop() {
        val d = drops.firstOrNull { !it.active } ?: return
        // Bias toward the near field: uniform depth puts most drops at the
        // horizon where they're a pixel tall and the scene looks empty.
        val depth = random.nextFloat().pow(0.65f)
        d.active = true
        d.depth = depth
        d.x = random.nextFloat()
        d.landY = waterY(depth)
        d.y = horizonY() - FALL_HEIGHT * (0.4f + 0.6f * depth) * random.nextFloat()
        d.speed = FALL_SPEED * (0.45f + 0.55f * depth)
    }

    private fun spawnRipple(x: Float, y: Float, depth: Float) {
        val r = ripples.firstOrNull { !it.active }
            ?: ripples.maxByOrNull { it.age / it.life } ?: return
        r.active = true
        r.x = x
        r.y = y
        r.depth = depth
        r.age = 0f
        r.life = RIPPLE_LIFE * (0.7f + 0.5f * depth)
        r.strength = 0.5f + 0.5f * depth
    }

    /** Water line sits higher in frame as the mix gets louder. */
    fun horizonY(): Float = HORIZON_REST - HORIZON_TRAVEL * tide

    /** Where a drop at [depth] meets the water, in normalised y. */
    fun waterY(depth: Float): Float {
        val h = horizonY()
        return h + (1f - h) * depth.pow(1.7f)
    }

    internal fun forEachDrop(action: (x: Float, y: Float, depth: Float) -> Unit) {
        for (d in drops) if (d.active) action(d.x, d.y, d.depth)
    }

    internal fun forEachRipple(
        action: (x: Float, y: Float, depth: Float, progress: Float, strength: Float) -> Unit,
    ) {
        for (r in ripples) if (r.active) action(r.x, r.y, r.depth, r.age / r.life, r.strength)
    }

    private fun coefficient(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

    private companion object {
        // Tightened from 0.40/0.12 — the original constants made density/brightness visibly
        // lag the beat by a few hundred ms. Faster envelopes here plus a shorter fall (below)
        // are what actually fixes perceived latency, not just polling more often.
        const val TIDE_TAU = 0.14f
        const val SHIMMER_TAU = 0.045f
        const val BASE_RATE = 6f
        const val RATE_RANGE = 70f
        const val BURST_DROPS = 14f
        const val ONSET_THRESHOLD = 0.35f
        // Faster fall means a burst spawned on a transient reaches the water — and reads as a
        // hit — much sooner. This was the dominant source of latency: a drop used to take up to
        // ~480ms to fall before its ripple appeared, so onsets read as late no matter how fast
        // the density/level tracking was.
        const val FALL_SPEED = 2.6f
        const val FALL_HEIGHT = 0.55f
        const val RIPPLE_LIFE = 1.5f
        const val HORIZON_REST = 0.44f
        const val HORIZON_TRAVEL = 0.05f
    }
}

@Composable
fun RainVisualizer(
    analyzer: SpectrumAnalyzer,
    modifier: Modifier = Modifier,
) {
    val state = remember(analyzer) { RainVisualizerState() }
    var tick by remember { mutableLongStateOf(0L) }
    val palette = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkPalette else LightPalette

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
        tick // read so the draw scope re-runs every frame

        drawRect(Brush.verticalGradient(colorStops = palette.waterStops))

        val horizon = state.horizonY() * size.height
        val lift = 0.5f + 0.5f * state.tide

        // Faint mist band where the water meets the dark, so the plane has an edge.
        drawRect(
            brush = Brush.verticalGradient(
                0f to palette.ghostGlow.copy(alpha = 0f),
                0.5f to palette.ghostGlow.copy(alpha = 0.10f * lift),
                1f to palette.ghostGlow.copy(alpha = 0f),
            ),
            topLeft = Offset(0f, horizon - size.height * 0.05f),
            size = Size(size.width, size.height * 0.10f),
        )

        state.forEachRipple { x, y, depth, progress, strength ->
            drawRipple(palette, x, y, depth, progress, strength * lift)
        }

        state.forEachDrop { x, y, depth ->
            drawDrop(palette, x, y, depth, lift)
        }
    }
}

/**
 * Expanding ellipse. The vertical squash is what makes the water read as a
 * receding plane rather than a wall — a true circle looks like rain on glass.
 */
private fun DrawScope.drawRipple(
    palette: RainPalette,
    nx: Float,
    ny: Float,
    depth: Float,
    progress: Float,
    strength: Float,
) {
    val eased = 1f - (1f - progress).pow(2.2f)
    val radius = size.width * RIPPLE_MAX_RADIUS * (0.25f + 0.75f * depth) * eased
    val squash = 0.22f + 0.16f * depth
    val fade = (1f - progress).pow(1.6f) * strength

    val cx = nx * size.width
    val cy = ny * size.height

    // Glow is stacked strokes, not a blur filter: BlurMaskFilter forces a
    // software layer on some devices and tanks the frame rate here.
    glowOval(palette, cx, cy, radius, radius * squash, fade)

    // Trailing inner ring, so a splash reads as more than a single expanding O.
    val inner = progress - 0.22f
    if (inner > 0f) {
        val innerEased = 1f - (1f - inner).pow(2.2f)
        val ir = size.width * RIPPLE_MAX_RADIUS * (0.25f + 0.75f * depth) * innerEased
        glowOval(palette, cx, cy, ir, ir * squash, fade * 0.45f)
    }
}

private fun DrawScope.glowOval(palette: RainPalette, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
    if (alpha <= 0.01f || rx <= 0.5f) return
    val topLeft = Offset(cx - rx, cy - ry)
    val dims = Size(rx * 2f, ry * 2f)
    // Wide, faint outer halo first, then two tighter passes — against near-black water this
    // is what actually reads as "glowing" rather than just "outlined."
    drawOval(palette.ghostGlow.copy(alpha = alpha * 0.10f), topLeft, dims, style = Stroke(14f))
    drawOval(palette.ghostGlow.copy(alpha = alpha * 0.22f), topLeft, dims, style = Stroke(7f))
    drawOval(palette.ghostGlow.copy(alpha = alpha * 0.45f), topLeft, dims, style = Stroke(3f))
    drawOval(palette.ghost.copy(alpha = alpha), topLeft, dims, style = Stroke(1.3f))
}

/** A falling drop: a short vertical streak, longer and brighter up close. */
private fun DrawScope.drawDrop(palette: RainPalette, nx: Float, ny: Float, depth: Float, lift: Float) {
    val x = nx * size.width
    val y = ny * size.height
    val len = size.height * (0.012f + 0.030f * depth)
    val alpha = (0.28f + 0.65f * depth) * lift
    val top = Offset(x, y - len)
    val bottom = Offset(x, y)

    drawLine(palette.ghostGlow.copy(alpha = alpha * 0.30f), top, bottom, 7f, StrokeCap.Round)
    drawLine(palette.ghostGlow.copy(alpha = alpha * 0.45f), top, bottom, 3.5f, StrokeCap.Round)
    drawLine(palette.ghost.copy(alpha = alpha), top, bottom, 1.4f, StrokeCap.Round)
}

private const val RIPPLE_MAX_RADIUS = 0.16f
