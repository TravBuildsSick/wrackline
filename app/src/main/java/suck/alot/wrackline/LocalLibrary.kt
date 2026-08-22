package suck.alot.wrackline

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/** Scans the device's own music library via MediaStore — no user file-picking involved. */
fun queryLocalTracks(context: Context): List<Track> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.ARTIST,
    )
    // IS_MUSIC/DURATION can be NULL for a freshly-added file MediaStore hasn't finished
    // extracting metadata for yet — a plain `!= 0` / `>= 15000` comparison against NULL is
    // neither true nor false in SQL, so it silently drops real songs, not just junk. Treat
    // NULL as "don't know yet, include it" rather than "exclude it".
    val selection = "(${MediaStore.Audio.Media.IS_MUSIC} IS NULL OR ${MediaStore.Audio.Media.IS_MUSIC} != 0) " +
        "AND (${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} >= 15000)"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

    val tracks = mutableListOf<Track>()
    context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        var artIndex = 0
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(collection, id)
            val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                ?: cursor.getString(nameCol)
                ?: "Unknown track"
            // MediaStore reports "<unknown>" rather than NULL for genuinely unset artist tags.
            val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
            tracks.add(Track(id = uri.toString(), uri = uri, name = title, artist = artist, artIndex = artIndex))
            artIndex++
        }
    }
    return tracks
}
