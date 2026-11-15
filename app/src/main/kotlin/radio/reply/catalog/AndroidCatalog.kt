package radio.reply.catalog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Process
import android.util.AtomicFile
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.Executors
import radio.reply.context.ContextJson

object SafSelection {
    fun intent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "audio/mpeg"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    fun result(resultCode: Int, data: Intent?): DocumentSelection? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val clip = data.clipData
        // Reject oversized batches instead of silently requesting access to an unbounded list.
        if (clip != null && clip.itemCount > LocalMusicCatalog.MAX_SELECTION) return DocumentSelection(emptyList(), false, false)
        val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.toString() }
            else listOfNotNull(data.data?.toString())
        return DocumentSelection(uris.distinct(), data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }
}

class AndroidDocumentAccess(context: Context) : DocumentAccess {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    override fun hasPersistedRead(uri: String) = resolver.persistedUriPermissions.any {
        it.uri == Uri.parse(uri) && it.isReadPermission
    }
    override fun hasRead(uri: String) = hasPersistedRead(uri) || app.checkUriPermission(Uri.parse(uri),
        Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    override fun persistRead(uri: String): Boolean = try {
        resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        hasPersistedRead(uri)
    } catch (_: SecurityException) { false }
    override fun releaseRead(uri: String) {
        if (hasPersistedRead(uri)) resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    override fun metadata(uri: String): RawMetadata {
        require(LocalMusicCatalog.validUri(uri))
        resolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { descriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                if (descriptor.declaredLength >= 0) retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                else retriever.setDataSource(descriptor.fileDescriptor)
                fun tag(key: Int) = retriever.extractMetadata(key)
                return RawMetadata(tag(MediaMetadataRetriever.METADATA_KEY_TITLE), tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    tag(MediaMetadataRetriever.METADATA_KEY_YEAR), tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(), tag(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            } finally { retriever.release() }
        }
        throw java.io.FileNotFoundException("DOCUMENT_UNAVAILABLE")
    }
}

class AndroidCatalogStore(context: Context, fileName: String = "local-music-catalog.json") : CatalogStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    override fun load(): List<CatalogEntry> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val data = input.readBytesBounded(4 * 1024 * 1024)
            data
        }
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root["schemaVersion"].asInt == 1) { "UNSUPPORTED_CATALOG_VERSION" }
        val entries = root.getAsJsonArray("entries")
        require(entries.size() <= LocalMusicCatalog.MAX_ITEMS)
        // Do not silently replace a damaged catalog with an empty one.
        return entries.map { value ->
            val entry = ContextJson.gson.fromJson(value, CatalogEntry::class.java)
            require(entry.id.isNotBlank() && LocalMusicCatalog.validUri(entry.uri))
            requireNotNull(entry.status); requireNotNull(entry.access)
            requireNotNull(entry.title); requireNotNull(entry.artist)
            entry
        }
    }
    override fun save(entries: List<CatalogEntry>) {
        val bytes = ContextJson.encode(mapOf("schemaVersion" to 1, "entries" to entries)).toByteArray(Charsets.UTF_8)
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            require(output.size() + n <= limit) { "CATALOG_TOO_LARGE" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}

/** Process owner: one IO queue, application context only, no Activity retained. */
class CatalogRuntime private constructor(context: Context) {
    val io = Executors.newSingleThreadExecutor()
    val catalog = LocalMusicCatalog(AndroidDocumentAccess(context), AndroidCatalogStore(context))
    private var restored = false
    fun load(): CatalogResult = if (restored) catalog.refresh() else catalog.restore().also { restored = true }
    companion object {
        @Volatile private var instance: CatalogRuntime? = null
        fun get(context: Context): CatalogRuntime = instance ?: synchronized(this) {
            instance ?: CatalogRuntime(context.applicationContext).also { instance = it }
        }
    }
}
