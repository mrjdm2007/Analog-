package com.mrjdm2007.analog

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.media.MediaMetadataRetriever
import java.util.ArrayDeque
import java.util.UUID

/**
 * Reads audio from user-granted Storage Access Framework trees.
 *
 * This supplements MediaStore rather than replacing it: files that Android has
 * not indexed can still be discovered and played through their persisted content URI.
 */
internal object SafAudioLibrary {
    private val audioExtensions = setOf(
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif",
        "ape", "wma", "alac", "mka", "mp4"
    )

    fun scan(context: Context, treeUris: Set<String>): List<Track> {
        if (treeUris.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        val result = LinkedHashMap<String, Track>()
        val queue = ArrayDeque<Folder>()

        for (raw in treeUris) {
            val tree = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val rootName = rootId.substringAfter(':').substringAfterLast('/').ifBlank { "Selected folder" }
            queue.add(Folder(tree, rootId, rootName, 0))
        }

        val visited = HashSet<String>()
        var examined = 0
        while (queue.isNotEmpty() && examined < 20_000) {
            val folder = queue.removeFirst()
            val visitKey = folder.treeUri.toString() + "|" + folder.documentId
            if (!visited.add(visitKey)) continue
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(folder.treeUri, folder.documentId)
            }.getOrNull() ?: continue

            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val rows = runCatching {
                resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                    buildList {
                        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (idCol >= 0 && nameCol >= 0 && mimeCol >= 0) {
                            while (cursor.moveToNext()) {
                                val id = cursor.getString(idCol) ?: continue
                                val name = cursor.getString(nameCol) ?: continue
                                val mime = cursor.getString(mimeCol).orEmpty()
                                val modified = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L
                                add(Child(id, name, mime, modified))
                            }
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())

            for (child in rows) {
                examined++
                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (folder.depth < 32) {
                        queue.add(Folder(folder.treeUri, child.documentId, child.displayName, folder.depth + 1))
                    }
                    continue
                }
                val extension = child.displayName.substringAfterLast('.', "").lowercase()
                if (!child.mimeType.startsWith("audio/") && extension !in audioExtensions) continue
                if (child.displayName.startsWith(".")) continue

                val documentUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, child.documentId)
                }.getOrNull() ?: continue
                val key = documentUri.toString()
                if (result.containsKey(key)) continue

                val metadata = readMetadata(context, documentUri, child.displayName) ?: continue
                val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).mostSignificantBits and Long.MAX_VALUE
                result[key] = Track(
                    id = if (id == 0L) 1L else id,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    uri = documentUri,
                    duration = metadata.duration,
                    folder = folder.relativeName,
                    albumId = 0L,
                    path = key,
                    dateAdded = (child.lastModified / 1000L).coerceAtLeast(0L),
                    isMusic = true
                )
            }
        }
        return result.values.toList()
    }

    private data class Folder(
        val treeUri: Uri,
        val documentId: String,
        val relativeName: String,
        val depth: Int
    )

    private data class Child(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val lastModified: Long
    )

    private data class Metadata(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long
    )

    private fun readMetadata(context: Context, uri: Uri, fileName: String): Metadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fileName.substringBeforeLast('.', fileName)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: "Unknown album"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            Metadata(title, artist, album, duration)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
