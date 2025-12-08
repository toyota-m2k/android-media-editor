package io.github.toyota32k.lib.media.editor.handler

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.media.lib.converter.AndroidFile
import java.io.File

/**
 * MediaStore (API29+) または、External Storage (API28-) へのメディアファイル保存用ヘルパークラス
 */
object AndroidMediaFile {
    val logger = AmeGlobal.logger

    fun createVideoFile(context:Context, filename:String, subFolder:String?=null, mimeType:String="video/mp4"): AndroidFile? {
        return if(Build.VERSION.SDK_INT>= Build.VERSION_CODES.Q) {
            // Android 10+
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                if (!subFolder.isNullOrBlank()) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$subFolder")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            AndroidFile(uri, context)
        } else {
            // Android 9-
            val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val targetFolder = if (!subFolder.isNullOrBlank()) {
                val subFolder = File(folder, subFolder)
                if (!subFolder.exists()) {
                    subFolder.mkdirs()
                }
                subFolder
            } else {
                folder
            }
            val outputFile = File(targetFolder, filename)
            AndroidFile(outputFile)
        }
    }

    fun createImageFile(context:Context, filename:String, subFolder:String?=null, mimeType:String="image/jpeg"): AndroidFile? {
        return if(Build.VERSION.SDK_INT>= Build.VERSION_CODES.Q) {
            // Android 10+
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (!subFolder.isNullOrBlank()) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$subFolder")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            AndroidFile(uri, context)
        } else {
            // Android 9-
            val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetFolder = if (!subFolder.isNullOrBlank()) {
                val subFolder = File(folder, subFolder)
                if (!subFolder.exists()) {
                    subFolder.mkdirs()
                }
                subFolder
            } else {
                folder
            }
            val outputFile = File(targetFolder, filename)
            AndroidFile(outputFile)
        }
    }


    @Suppress("unused")
    fun AndroidFile.expose(context: Context) {
        if(Build.VERSION.SDK_INT< Build.VERSION_CODES.Q) {
            // ACTION_MEDIA_SCANNER_SCAN_FILEは、
            // Deprecated
            // Callers should migrate to inserting items directly into MediaStore, where they will be automatically scanned after each mutation.
            // と書かれているが、これは、Android 10 以上の話で、Android 9 以下の場合は、これを使わざるを得ないのだ。ターゲットOSを Android 10 以上にできたら消す。
            val uri = this.uri ?: this.path?.toUri() ?: return
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
        }
    }

    fun AndroidFile.saveBitmap(bitmap: Bitmap, format:Bitmap.CompressFormat, quality:Int) {
        fileOutputStream { outputStream ->
            bitmap.compress(format, quality, outputStream)
            outputStream.flush()
        }
    }
    fun AndroidFile.safeSaveBitmap(bitmap: Bitmap, format:Bitmap.CompressFormat, quality:Int):Boolean {
        return try {
            saveBitmap(bitmap, format, quality)
            true
        } catch (e: Throwable) {
            AmeGlobal.logger.error(e)
            false
        }
    }
}