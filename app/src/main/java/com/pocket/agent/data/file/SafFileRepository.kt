package com.pocket.agent.data.file

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import com.pocket.agent.util.FileNames
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.fileDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_files")

/** One row of the directory listing shown on the files screen. */
data class SafFileEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** Thrown when the requested name already exists and overwrite was not allowed. */
class FileExistsException(val fileName: String) :
    Exception("文件已存在：$fileName")

/** Thrown when a file exceeds [FileNames.MAX_FILE_BYTES]. */
class FileTooLargeException(val limitBytes: Long) :
    Exception("文件超过大小限制（${limitBytes / 1024} KB）")

/** Thrown when a binary document exceeds [MAX_BINARY_FILE_BYTES]. */
class BinaryTooLargeException(val limitBytes: Long) :
    Exception("文件超过大小限制（${limitBytes / 1024 / 1024} MB）")

/**
 * All file access goes through the Storage Access Framework.
 *
 * The user picks a directory once with `ACTION_OPEN_DOCUMENT_TREE`; the returned
 * tree URI is persisted with `takePersistableUriPermission` so the grant
 * survives reboots. No storage permission is requested and the app cannot see
 * anything outside the chosen tree.
 */
class SafFileRepository(
    context: Context,
) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dataStore = appContext.fileDataStore

    /** The directory the agent works in, or null when none has been picked. */
    suspend fun getCurrentDirUri(): Uri? = withContext(Dispatchers.IO) {
        val stored = dataStore.data.map { it[KEY_TREE_URI] }.first()
        val uri = stored?.let(Uri::parse) ?: return@withContext null
        if (hasPersistedPermission(uri)) uri else null
    }

    val currentDirUriFlow: Flow<Uri?> = dataStore.data.map { prefs ->
        prefs[KEY_TREE_URI]?.let(Uri::parse)?.takeIf { hasPersistedPermission(it) }
    }

    /** Files of the current directory, refreshed whenever the directory changes. */
    val filesFlow: Flow<List<SafFileEntry>> = currentDirUriFlow.map { uri ->
        if (uri == null) emptyList() else listFiles(uri)
    }

    /** Display name of the current directory, or null when none has been picked. */
    suspend fun currentDirName(): String? = withContext(Dispatchers.IO) {
        val uri = getCurrentDirUri() ?: return@withContext null
        DocumentFile.fromTreeUri(appContext, uri)?.name
    }

    /**
     * Persists a directory chosen by the picker. Returns false when the provider
     * refuses to grant a durable permission, which some cloud providers do.
     */
    suspend fun setCurrentDir(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "Persistable permission refused", e)
            return@withContext false
        }
        val document = DocumentFile.fromTreeUri(appContext, treeUri)
        if (document == null || !document.isDirectory) {
            Log.w(TAG, "Picked URI is not a directory: $treeUri")
            return@withContext false
        }
        dataStore.edit { it[KEY_TREE_URI] = treeUri.toString() }
        true
    }

    suspend fun clearCurrentDir() = withContext(Dispatchers.IO) {
        val stored = dataStore.data.map { it[KEY_TREE_URI] }.first()?.let(Uri::parse)
        if (stored != null) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    stored,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        dataStore.edit { it.remove(KEY_TREE_URI) }
        Unit
    }

    suspend fun listFiles(dirUri: Uri): List<SafFileEntry> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(appContext, dirUri) ?: return@withContext emptyList()
        dir.listFiles()
            .map { file ->
                SafFileEntry(
                    uri = file.uri,
                    name = file.name ?: file.uri.lastPathSegment.orEmpty(),
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) 0 else file.length(),
                    lastModified = file.lastModified(),
                )
            }
            .sortedWith(compareByDescending<SafFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun createFile(
        dirUri: Uri,
        fileName: String,
        content: String,
        mimeType: String = DEFAULT_MIME_TYPE,
        overwrite: Boolean = false,
    ): SafFileEntry = withContext(Dispatchers.IO) {
        val nameError = FileNames.error(fileName)
        if (nameError != null) throw IllegalArgumentException(nameError)
        if (content.toByteArray(Charsets.UTF_8).size > FileNames.MAX_FILE_BYTES) {
            throw FileTooLargeException(FileNames.MAX_FILE_BYTES)
        }

        val dir = DocumentFile.fromTreeUri(appContext, dirUri)
            ?: throw IllegalStateException("目录不可访问，请重新选择保存目录")
        val existing = dir.findFile(fileName.trim())
        val target = when {
            existing == null -> dir.createFile(mimeType, fileName.trim())
            overwrite -> existing
            else -> throw FileExistsException(fileName.trim())
        } ?: throw IllegalStateException("无法创建文件：$fileName")

        writeTo(target.uri, content)
        SafFileEntry(
            uri = target.uri,
            name = target.name ?: fileName.trim(),
            isDirectory = false,
            sizeBytes = target.length(),
            lastModified = target.lastModified(),
        )
    }

    suspend fun readText(fileUri: Uri): String = withContext(Dispatchers.IO) {
        val length = runCatching {
            DocumentFile.fromSingleUri(appContext, fileUri)?.length()
        }.getOrNull()
        if (length != null && length > FileNames.MAX_FILE_BYTES) {
            throw FileTooLargeException(FileNames.MAX_FILE_BYTES)
        }
        resolver.openInputStream(fileUri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val text = reader.readText()
                if (text.toByteArray(Charsets.UTF_8).size > FileNames.MAX_FILE_BYTES) {
                    throw FileTooLargeException(FileNames.MAX_FILE_BYTES)
                }
                text
            }
        } ?: throw IllegalStateException("无法读取文件")
    }

    /**
     * Creates a binary file - a generated PDF, DOCX or PPTX - in [dirUri].
     *
     * Generated documents legitimately run past the 1 MB text ceiling, so this
     * path enforces the larger [MAX_BINARY_FILE_BYTES] instead. The overwrite
     * semantics mirror [createFile]: an existing file is replaced only when
     * [overwrite] is set.
     */
    suspend fun createBinaryFile(
        dirUri: Uri,
        fileName: String,
        content: ByteArray,
        mimeType: String,
        overwrite: Boolean = false,
    ): SafFileEntry = withContext(Dispatchers.IO) {
        val nameError = FileNames.error(fileName)
        if (nameError != null) throw IllegalArgumentException(nameError)
        if (content.size.toLong() > MAX_BINARY_FILE_BYTES) {
            throw BinaryTooLargeException(MAX_BINARY_FILE_BYTES)
        }

        val dir = DocumentFile.fromTreeUri(appContext, dirUri)
            ?: throw IllegalStateException("目录不可访问，请重新选择保存目录")
        val existing = dir.findFile(fileName.trim())
        val target = when {
            existing == null -> dir.createFile(mimeType, fileName.trim())
            overwrite -> existing
            else -> throw FileExistsException(fileName.trim())
        } ?: throw IllegalStateException("无法创建文件：$fileName")

        writeBytesTo(target.uri, content)
        SafFileEntry(
            uri = target.uri,
            name = target.name ?: fileName.trim(),
            isDirectory = false,
            sizeBytes = target.length(),
            lastModified = target.lastModified(),
        )
    }

    suspend fun writeText(fileUri: Uri, content: String): Unit = withContext(Dispatchers.IO) {
        if (content.toByteArray(Charsets.UTF_8).size > FileNames.MAX_FILE_BYTES) {
            throw FileTooLargeException(FileNames.MAX_FILE_BYTES)
        }
        writeTo(fileUri, content)
    }

    suspend fun delete(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(appContext, fileUri)
            ?: return@withContext false
        document.delete()
    }

    suspend fun exists(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(appContext, fileUri) ?: return@withContext false
        document.exists()
    }

    /** Resolves a name inside a directory, or null when it is not there. */
    suspend fun findFile(dirUri: Uri, fileName: String): SafFileEntry? =
        withContext(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(appContext, dirUri) ?: return@withContext null
            val file = dir.findFile(fileName.trim()) ?: return@withContext null
            SafFileEntry(
                uri = file.uri,
                name = file.name ?: fileName,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0 else file.length(),
                lastModified = file.lastModified(),
            )
        }

    /** True when the directory can still be written to. */
    suspend fun isWritable(dirUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(appContext, dirUri) ?: return@withContext false
        dir.canWrite()
    }

    private fun writeTo(uri: Uri, content: String) {
        // "wt" truncates the existing document instead of appending to it.
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("无法写入文件")
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    private fun writeBytesTo(uri: Uri, content: ByteArray) {
        val stream = resolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("无法写入文件")
        stream.use { it.write(content) }
    }

    private fun hasPersistedPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    companion object {
        const val DEFAULT_MIME_TYPE = "text/plain"

        /** Ceiling for generated binary documents; well above any real output. */
        const val MAX_BINARY_FILE_BYTES: Long = 32L * 1024 * 1024

        private const val TAG = "SafFileRepository"

        val KEY_TREE_URI = stringPreferencesKey("save_dir_tree_uri")
    }
}
