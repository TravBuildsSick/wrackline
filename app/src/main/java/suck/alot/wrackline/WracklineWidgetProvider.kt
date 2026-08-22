package suck.alot.wrackline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

private const val ACTION_PREV = "suck.alot.wrackline.widget.PREV"
private const val ACTION_PLAY_PAUSE = "suck.alot.wrackline.widget.PLAY_PAUSE"
private const val ACTION_NEXT = "suck.alot.wrackline.widget.NEXT"

/**
 * Home-screen widget: track/artist + prev/play-pause/next. Talks to PlaybackService through a
 * short-lived MediaController per button press, same pattern the Activity uses, rather than
 * holding a persistent connection the widget process can't reliably keep alive.
 */
class WracklineWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, trackName = "Wrackline", artist = "Not playing", isPlaying = false))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action !in setOf(ACTION_PREV, ACTION_PLAY_PAUSE, ACTION_NEXT)) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                val controller = controllerFuture.get()
                when (action) {
                    ACTION_PREV -> controller.seekToPrevious()
                    ACTION_PLAY_PAUSE -> if (controller.isPlaying) controller.pause() else controller.play()
                    ACTION_NEXT -> controller.seekToNext()
                }
                MediaController.releaseFuture(controllerFuture)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    companion object {
        /** Called by PlaybackService whenever playback state changes. */
        fun updateAll(context: Context, trackName: String, artist: String?, isPlaying: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WracklineWidgetProvider::class.java))
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, trackName, artist ?: "Unknown artist", isPlaying))
            }
        }

        private fun buildViews(context: Context, trackName: String, artist: String, isPlaying: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_wrackline)
            views.setTextViewText(R.id.widget_track_name, trackName)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setOnClickPendingIntent(R.id.widget_prev, actionIntent(context, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widget_play_pause, actionIntent(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, actionIntent(context, ACTION_NEXT))
            return views
        }

        private fun actionIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, WracklineWidgetProvider::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
