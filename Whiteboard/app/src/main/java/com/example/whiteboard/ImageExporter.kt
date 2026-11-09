package com.example.whiteboard

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves bitmaps to Pictures/Whiteboard. Blocking: call from a background thread. */
object ImageExporter {

    private const val FOLDER = "Whiteboard"

    @Throws(IOException::class)
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Whiteboard_$stamp.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, bitmap, name)
        } else {
            saveLegacy(context, bitmap, name)
        }
    }

    /** API 29+: Scoped Storage via MediaStore. No permission required. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(context: Context, bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1) // hidden from other apps until finished
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("PNG compression failed")
                }
            } ?: throw IOException("Could not open output stream")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a broken entry behind
            throw e
        }
        return uri
    }

    /** API 26-28: needs WRITE_EXTERNAL_STORAGE (requested at runtime by the activity). */
    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, name: String): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir")

        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("PNG compression failed")
            }
        }
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(file.absolutePath),
            arrayOf("image/png"),
            null
        )
        return Uri.fromFile(file)
    }
}
