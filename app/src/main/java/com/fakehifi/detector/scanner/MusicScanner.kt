package com.fakehifi.detector.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.fakehifi.detector.model.TrackInfo

object MusicScanner {

    /**
     * Only lossless/hi-res containers can meaningfully be "fake" — a real
     * lossy file (mp3/aac/ogg) isn't claiming to be anything it's not, so we
     * skip those and focus the scan on FLAC/WAV/ALAC/APE/AIFF.
     */
    fun findAllTracks(context: Context, folderUriString: String? = null): List<TrackInfo> {
        val tracks = mutableListOf<TrackInfo>()
        
        if (folderUriString != null && folderUriString.startsWith("content://com.android.externalstorage.documents/tree/")) {
            // This is likely a SAF Tree Uri (SD card, USB, etc)
            // We'll iterate it using DocumentFile or recursive ContentResolver queries
            iterateSafTree(context, Uri.parse(folderUriString), tracks)
            return tracks
        }

        var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val selectionArgs = mutableListOf<String>()
        
        if (folderUriString != null) {
            val folderPath = getPathFromUri(context, android.net.Uri.parse(folderUriString))
            if (folderPath != null) {
                selection += " AND ${MediaStore.Audio.Media.DATA} LIKE ?"
                selectionArgs.add("$folderPath%")
            }
        }

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION
        )

        context.contentResolver.query(
            collection, projection, selection, 
            if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(),
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateModCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val mime = cursor.getString(mimeCol) ?: ""

                if (!isLosslessOrHiRes(mime)) continue

                tracks.add(
                    TrackInfo(
                        uri = uri,
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown",
                        filePath = cursor.getString(dataCol) ?: "",
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateModCol),
                        durationMs = cursor.getLong(durCol)
                    )
                )
            }
        }
        return tracks
    }

    private fun getPathFromUri(context: Context, uri: android.net.Uri): String? {
        // Very basic conversion for common paths. In a real app, 
        // we'd use a more robust DocumentFile to MediaStore path mapper.
        if (uri.scheme == "content") {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            if (split.size >= 2) {
                val type = split[0]
                val relativePath = split[1]
                if ("primary".equals(type, ignoreCase = true)) {
                    return android.os.Environment.getExternalStorageDirectory().toString() + "/" + relativePath
                }
            }
        }
        return uri.path
    }

    fun getTrackInfoFromUri(context: Context, uri: Uri): TrackInfo? {
        var title = "Unknown"
        var artist = "Unknown"
        var size = 0L
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) title = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        
        return TrackInfo(
            uri = uri.toString(),
            title = title,
            artist = artist,
            filePath = uri.path ?: "",
            sizeBytes = size,
            dateAdded = System.currentTimeMillis() / 1000,
            durationMs = 0
        )
    }

    private fun iterateSafTree(context: Context, treeUri: Uri, results: MutableList<TrackInfo>) {
        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            treeUri, 
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        )
        queryRecursive(context, treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri), results)
    }

    private fun queryRecursive(context: Context, treeUri: Uri, parentId: String, results: MutableList<TrackInfo>) {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE,
            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_SIZE)
            val modCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol)
                val name = cursor.getString(nameCol)
                val mime = cursor.getString(mimeCol) ?: ""
                
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    queryRecursive(context, treeUri, id, results)
                } else if (isLosslessOrHiRes(mime) || isLosslessExt(name)) {
                    val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    results.add(TrackInfo(
                        uri = docUri.toString(),
                        title = name,
                        artist = "External Drive",
                        filePath = name,
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(modCol) / 1000,
                        durationMs = 0
                    ))
                }
            }
        }
    }

    private fun isLosslessExt(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".alac") || n.endsWith(".m4a") || n.endsWith(".aiff") || n.endsWith(".ape")
    }

    private fun isLosslessOrHiRes(mime: String): Boolean {
        val m = mime.lowercase()
        return m.contains("flac") || m.contains("wav") ||
            m.contains("alac") || m.contains("ape") || m.contains("aiff") ||
            m.contains("x-m4a") || m.contains("mp4a.40.1") || // ALAC in M4A
            m.contains("apple-lossless")
    }
}
