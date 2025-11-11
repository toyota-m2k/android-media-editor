package io.github.toyota32k.lib.media.editor.handler

import io.github.toyota32k.dialog.broker.IUtActivityBrokerStoreProvider
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.lib.media.editor.dialog.NameDialog
import io.github.toyota32k.lib.media.editor.dialog.SaveOptionDialog
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.toAndroidFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtil {
    /**
     * コンテントタイプから拡張子を求める
     */
    fun contentType2Ext(contentType: String): String {
        return when (val ct = contentType.lowercase()) {
            "image/png"->".png"
            "image/jpeg"->".jpg"
            "image/gif"->".gif"
            "video/mp4"->".mp4"
            else -> {
                if (ct.startsWith("video/")) {
                    ".mp4"
                } else if(ct.startsWith("image/")) {
                    ".jpg"
                } else {
                    ""
                }
            }
        }
    }

    /**
     * ファイルの拡張子を取得
     */
    fun getExtension(file:AndroidFile):String {
        return contentType2Ext(file.getContentType()?:"")
    }

    /**
     * 保存ファイル名の初期値を作成
     */
    fun createInitialFileName(originalName: String, suffix:String, ext: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalName.take(dotIndex) else originalName
        return "${baseName}${suffix}$ext"
    }

    /**
     * ピッカーを表示して保存ファイルを選択
     */
    suspend fun selectFile(type: String, initialFileName: String): AndroidFile? {
        val owner = UtImmortalTaskManager.mortalInstanceSource.getOwner()
        val pickerProvider = owner.lifecycleOwner as? IUtActivityBrokerStoreProvider ?: owner.asActivity() as? IUtActivityBrokerStoreProvider ?: return null
        val filePicker = pickerProvider.activityBrokers.createFilePicker
        return filePicker.selectFile(initialFileName, type)?.toAndroidFile(owner.application)
    }
//    suspend fun selectFile(type:String, srcFile:AndroidFile, outputFileSuffix:String, ext:String=getExtension(srcFile)):AndroidFile? {
//        val initialFileName = createInitialFileName(srcFile.getFileName()?:"unnamed", outputFileSuffix, ext)
//        return selectFile(type, initialFileName)
//    }

    val dateFormatForFilename = SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US)
    fun defaultFileNameSuffix(): String {
        return dateFormatForFilename.format(Date())
    }
}

abstract class NamedFileProvider(val outputFileSuffix:String) : IOutputFileProvider {
    open suspend fun initialFileName(mimeType:String,inputFile: AndroidFile): String? {
        return FileUtil.createInitialFileName(inputFile.getFileName() ?: "unnamed", outputFileSuffix, FileUtil.contentType2Ext(mimeType))
    }
}

/**
 * ファイルピッカーを表示して保存先ファイルを選択する FileProvider
 */
open class ExportFileProvider(outputFileSuffix:String) : NamedFileProvider(outputFileSuffix) {
    override suspend fun getOutputFile(mimeType:String, inputFile: AndroidFile): AndroidFile? {
        val name = initialFileName(mimeType, inputFile) ?: return null
        return FileUtil.selectFile(mimeType, name)
    }
}

/**
 * 名前を付けて MediaStore にファイルを保存するための FileProvider
 */
open class MediaFileProvider(outputFileSuffix: String, val subFolder:String?=null) : NamedFileProvider(outputFileSuffix) {
    override suspend fun getOutputFile(mimeType: String, inputFile: AndroidFile): AndroidFile? {
        val name = initialFileName(mimeType, inputFile) ?: return null
        val ct = mimeType.lowercase()
        val owner = UtImmortalTaskManager.mortalInstanceSource.getOwner()
        return if (ct.startsWith("video/")) {
            AndroidMediaFile.createVideoFile(owner.application, name, subFolder, mimeType)
        } else if (ct.startsWith("image/")) {
            AndroidMediaFile.createImageFile(owner.application, name, subFolder, mimeType)
        } else {
            throw IllegalStateException("unsupported mime type: $mimeType")
        }
    }
}

/**
 * 保存先選択ダイアログを表示して、保存先ファイルを取得。
 */
open class InteractiveMediaFileProvider(subFolder:String?=null): MediaFileProvider("", subFolder) {
    override suspend fun initialFileName(mimeType: String, inputFile: AndroidFile): String? {
        val initialName = FileUtil.createInitialFileName(inputFile.getFileName() ?: "unnamed", outputFileSuffix, FileUtil.contentType2Ext(mimeType))
        return NameDialog.show(initialName)
    }
}

/**
 * 名前を指定して Media Store に保存する FileProvider
 */
open class NamedMediaFileProvider(val name:String, subFolder:String?=null): MediaFileProvider("", subFolder) {
    override suspend fun initialFileName(mimeType: String, inputFile: AndroidFile): String? {
        return name
    }
}

/**
 * 編集中ファイルを上書きする FileProvider
 */
object OverwriteFileProvider : IOutputFileProvider {
    override suspend fun getOutputFile(mimeType:String, inputFile: AndroidFile): AndroidFile? {
        if (!inputFile.canWrite()) {
            AmeGlobal.logger.error("target file is not writable")
            return ExportFileProvider("").getOutputFile(mimeType, inputFile)
        }
        return inputFile
    }
}

class InteractiveOutputFileProvider(val outputFileSuffix:String, val subFolder:String?) : IOutputFileProvider {
    override suspend fun getOutputFile(mimeType: String, inputFile: AndroidFile): AndroidFile? {
        val initialName = FileUtil.createInitialFileName(inputFile.getFileName() ?: "unnamed", outputFileSuffix, FileUtil.contentType2Ext(mimeType))
        val provider = SaveOptionDialog.show(initialName, subFolder, outputFileSuffix) ?: return null
        return provider.getOutputFile(mimeType, inputFile)
    }

}

