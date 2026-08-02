package com.fakehifi.detector.scanner

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.fakehifi.detector.model.TrackInfo

object MusicScanner {

    /**
     * Only lossless/hi-res containers can meaningfully be "fake" — a real
     * lossy file (mp3/aac/ogg) isn't claiming to be anything it's not, so we
     * skip those and focus the scan on FLAC/WAV/ALAC/APE/AIFF.
     */
    fun findAllTracks(context: Context): List<TrackInfo> {
        val tracks = mutableListOf<TrackInfo>()

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
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(
            collection, projection, selection, null,
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
                        dateModifiedSec = cursor.getLong(dateModCol),
                        durationMs = cursor.getLong(durCol)
                    )
                )
            }
        }
        return tracks
    }

    private fun isLosslessOrHiRes(mime: String): Boolean {
        val m = mime.lowercase()
        return m.contains("flac") || m.contains("wav") ||
            m.contains("alac") || m.contains("ape") || m.contains("aiff")
    }
}
