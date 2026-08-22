package suck.alot.wrackline

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String>,
    val locked: Boolean,
)

private fun playlistsFile(context: Context) = File(context.filesDir, "playlists.json")

fun loadPlaylists(context: Context): List<Playlist> {
    val file = playlistsFile(context)
    if (!file.exists()) return emptyList()
    return try {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val ids = o.getJSONArray("trackIds")
            Playlist(
                id = o.getString("id"),
                name = o.getString("name"),
                trackIds = (0 until ids.length()).map { ids.getString(it) },
                locked = o.optBoolean("locked", false),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun savePlaylists(context: Context, playlists: List<Playlist>) {
    val array = JSONArray()
    for (p in playlists) {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        o.put("trackIds", JSONArray(p.trackIds))
        o.put("locked", p.locked)
        array.put(o)
    }
    playlistsFile(context).writeText(array.toString())
}

fun createPlaylist(context: Context, name: String): List<Playlist> {
    val current = loadPlaylists(context)
    val updated = current + Playlist(id = UUID.randomUUID().toString(), name = name, trackIds = emptyList(), locked = false)
    savePlaylists(context, updated)
    return updated
}

fun deletePlaylist(context: Context, playlistId: String): List<Playlist> {
    val updated = loadPlaylists(context).filterNot { it.id == playlistId && !it.locked }
    savePlaylists(context, updated)
    return updated
}

fun addTrackToPlaylist(context: Context, playlistId: String, trackId: String): List<Playlist> {
    val updated = loadPlaylists(context).map { p ->
        if (p.id == playlistId && !p.locked && trackId !in p.trackIds) {
            p.copy(trackIds = p.trackIds + trackId)
        } else {
            p
        }
    }
    savePlaylists(context, updated)
    return updated
}

fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String): List<Playlist> {
    val updated = loadPlaylists(context).map { p ->
        if (p.id == playlistId && !p.locked) p.copy(trackIds = p.trackIds - trackId) else p
    }
    savePlaylists(context, updated)
    return updated
}

/**
 * Ensures a locked, pack-derived playlist exists (created if missing, refreshed to match the
 * pack's current tracks otherwise) — called whenever a pack finishes installing. Locked
 * playlists always mirror their pack's contents; they can't be user-edited.
 */
fun ensureLockedPackPlaylist(context: Context, packName: String, trackIds: List<String>): List<Playlist> {
    val current = loadPlaylists(context)
    val existing = current.find { it.locked && it.name == packName }
    val updated = if (existing != null) {
        current.map { if (it.id == existing.id) it.copy(trackIds = trackIds) else it }
    } else {
        current + Playlist(id = UUID.randomUUID().toString(), name = packName, trackIds = trackIds, locked = true)
    }
    savePlaylists(context, updated)
    return updated
}
