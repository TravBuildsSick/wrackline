package suck.alot.wrackline

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

private const val GITHUB_OWNER = "TravBuildsSick"
private const val GITHUB_REPO = "wrackline"
private const val PACKS_URL =
    "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/packs.json"

data class MusicPack(
    val id: String,
    val name: String,
    val description: String,
    val preinstalled: Boolean,
    val releaseTag: String,
)

private fun packsDir(context: Context, packId: String) = File(context.filesDir, "packs/$packId")
private fun installedMarker(context: Context, packId: String) = File(packsDir(context, packId), ".installed")

fun isPackInstalled(context: Context, packId: String) = installedMarker(context, packId).exists()

fun fetchPackManifest(): List<MusicPack> {
    val connection = URL(PACKS_URL).openConnection() as HttpURLConnection
    connection.connectTimeout = 20000
    connection.readTimeout = 20000
    try {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val array: JSONArray = JSONArray(body)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MusicPack(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description", ""),
                preinstalled = o.optBoolean("preinstalled", false),
                releaseTag = o.getString("release_tag"),
            )
        }
    } finally {
        connection.disconnect()
    }
}

/** Downloads a pack's zip asset from its GitHub release and unzips it into app-private storage. */
fun downloadAndInstallPack(context: Context, pack: MusicPack) {
    val releaseUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/tags/${pack.releaseTag}"
    val releaseConn = URL(releaseUrl).openConnection() as HttpURLConnection
    releaseConn.connectTimeout = 20000
    releaseConn.readTimeout = 20000
    releaseConn.setRequestProperty("Accept", "application/vnd.github+json")
    val zipDownloadUrl: String
    try {
        val body = releaseConn.inputStream.bufferedReader().use { it.readText() }
        val assets = JSONObject(body).getJSONArray("assets")
        val zipAsset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .first { it.getString("name").endsWith(".zip") }
        zipDownloadUrl = zipAsset.getString("browser_download_url")
    } finally {
        releaseConn.disconnect()
    }

    val dir = packsDir(context, pack.id)
    dir.mkdirs()

    val zipConn = URL(zipDownloadUrl).openConnection() as HttpURLConnection
    zipConn.connectTimeout = 20000
    zipConn.readTimeout = 120000
    zipConn.instanceFollowRedirects = true
    try {
        ZipInputStream(zipConn.inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(dir, File(entry.name).name)
                    outFile.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } finally {
        zipConn.disconnect()
    }

    installedMarker(context, pack.id).writeText("ok")
}

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "ogg")

/** Tracks belonging to one specific pack, in the same order queryPackTracks would list them. */
fun queryTracksForPack(context: Context, packId: String, artIndexStart: Int = 0): List<Track> {
    val packDir = packsDir(context, packId)
    if (!File(packDir, ".installed").exists()) return emptyList()
    var artIndex = artIndexStart
    return packDir.listFiles { f -> f.extension.lowercase() in AUDIO_EXTENSIONS }
        ?.sortedBy { it.name }
        ?.map { file ->
            val uri = Uri.fromFile(file)
            val artist = try {
                android.media.MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                }
            } catch (e: Exception) {
                null
            }
            Track(id = uri.toString(), uri = uri, name = file.nameWithoutExtension, artist = artist, artIndex = artIndex++)
        } ?: emptyList()
}

/** All tracks from every pack already downloaded to app-private storage. */
fun queryPackTracks(context: Context): List<Track> {
    val packsRoot = File(context.filesDir, "packs")
    if (!packsRoot.exists()) return emptyList()
    val tracks = mutableListOf<Track>()
    packsRoot.listFiles()?.sortedBy { it.name }?.forEach { packDir ->
        tracks.addAll(queryTracksForPack(context, packDir.name, tracks.size))
    }
    return tracks
}
