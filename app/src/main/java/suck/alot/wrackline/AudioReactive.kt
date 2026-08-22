package suck.alot.wrackline

/**
 * The live spectrum analyzer, shared between PlaybackService (which feeds it PCM via
 * VisualizerSink/TeeAudioProcessor) and whichever Composable is polling analyzer.latest() this
 * frame. Safe as a plain singleton — SpectrumAnalyzer holds no Android resource handles, just
 * DSP state, so it can outlive any single PlaybackService instance.
 */
object AudioReactive {
    val analyzer = SpectrumAnalyzer()
}
