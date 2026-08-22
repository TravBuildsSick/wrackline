package suck.alot.wrackline

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process bridge from PlaybackService's Visualizer capture to the UI's canvas — safe because
 * the service and the activity always run in the same process here (no android:process split).
 */
object AudioReactive {
    val level = MutableStateFlow(0f)
}
