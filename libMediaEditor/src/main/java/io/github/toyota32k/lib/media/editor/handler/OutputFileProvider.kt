package io.github.toyota32k.lib.media.editor.handler

import androidx.documentfile.provider.DocumentFile
import io.github.toyota32k.dialog.broker.IUtActivityBrokerStoreProvider
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.dialog.task.showYesNoMessageBox
import io.github.toyota32k.lib.media.editor.dialog.NameDialog
import io.github.toyota32k.lib.media.editor.dialog.SaveOptionDialog
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.ICommonOutputFileProvider
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.lib.media.editor.model.ISaveResult
import io.github.toyota32k.media.lib.io.AndroidFile
import io.github.toyota32k.media.lib.io.toAndroidFile
import java.io.File

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
//    fun getExtension(file:AndroidFile):String {
//        return contentType2Ext(file.getContentType()?:"")
//    }

    fun getBaseName(file: AndroidFile):String? {
        val originalName = file.getFileName() ?: return null
        val dotIndex = originalName.lastIndexOf('.')
        return if (dotIndex > 0) originalName.take(dotIndex) else originalName
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

    suspend fun selectDirectory(): DocumentFile? {
        val owner = UtImmortalTaskManager.mortalInstanceSource.getOwner()
        val context = owner.application
        val pickerProvider = owner.lifecycleOwner as? IUtActivityBrokerStoreProvider ?: owner.asActivity() as? IUtActivityBrokerStoreProvider ?: return null
        val picker = pickerProvider.activityBrokers.directoryPicker
        val uri = picker.selectDirectory() ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    fun createWorkFile(subFolder:String?=null, prefix:String="ame-", suffix:String=".mp4"):AndroidFile {
        val owner = UtImmortalTaskManager.application
        val dir = if (!subFolder.isNullOrBlank()) {
            File(owner.cacheDir, subFolder).apply {
                mkdirs()
            }
        } else owner.cacheDir
        return File.createTempFile(prefix, suffix, dir).toAndroidFile()
    }

//    suspend fun selectFile(type:String, srcFile:AndroidFile, outputFileSuffix:String, ext:String=getExtension(srcFile)):AndroidFile? {
//        val initialFileName = createInitialFileName(srcFile.getFileName()?:"unnamed", outputFileSuffix, ext)
//        return selectFile(type, initialFileName)
//    }

//    val dateFormatForFilename = SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US)
//    fun defaultFileNameSuffix(): String {
//        return dateFormatForFilename.format(Date())
//    }
}

/**
 * 名前を付けて保存するた IOutputFileProvider の基底クラス.
 * inputFile名から outputFile名を作成するAPIを提供。
 *
 * @param outputFileSuffix inputFile から outputFile名を作成するときに付加するサフィックス
 */
abstract class AbstractNamedFileProvider(val outputFileSuffix:String) : IOutputFileProvider {
    open suspend fun getBaseFileName(inputFile:AndroidFile): String {
        return inputFile.getFileName() ?: "unnamed"
    }

    open suspend fun initialFileName(mimeType:String,inputFile: AndroidFile): String? {
        return FileUtil.createInitialFileName(getBaseFileName(inputFile), outputFileSuffix, FileUtil.contentType2Ext(mimeType))
    }

    override suspend fun finalize(result: ISaveResult) {
        if (!result.succeeded) {
            result.outputFile?.safeDelete()
        }
    }
}

/**
 * ファイルピッカーを表示して保存先ファイルを選択する FileProvider
 * 出力ファイル名の初期値の取得に、NamedFileProviderの実装を利用
 * @param outputFileSuffix inputFile から outputFile名を作成するときに付加するサフィックス
 */
open class ExportFileProvider(outputFileSuffix:String) : AbstractNamedFileProvider(outputFileSuffix), ICommonOutputFileProvider {
    override suspend fun getOutputFile(mimeType:String, name:String): AndroidFile? {
        return FileUtil.selectFile(mimeType, name)
    }
    override suspend fun getOutputFile(mimeType:String, inputFile: AndroidFile): AndroidFile? {
        val name = initialFileName(mimeType, inputFile) ?: return null
        return getOutputFile(mimeType, name)
    }
}

/**
 * 決め打ちの名前（NamedFileProvider使用）で、MediaStore にファイルを保存するための FileProvider
 * @param outputFileSuffix inputFile から outputFile名を作成するときに付加するサフィックス
 * @param subFolder Media Files のサブフォルダ名 (nullなら直下)
 */
open class MediaFileProvider(outputFileSuffix: String, val subFolder:String?=null) : AbstractNamedFileProvider(outputFileSuffix), ICommonOutputFileProvider {
    override suspend fun getOutputFile(mimeType:String, name:String): AndroidFile? {
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

    override suspend fun getOutputFile(mimeType: String, inputFile: AndroidFile): AndroidFile? {
        val name = initialFileName(mimeType, inputFile) ?: return null
        return getOutputFile(mimeType, name)
    }
}

/**
 * 保存先選択ダイアログを表示して、保存先ファイルを取得。
 * @param subFolder Media Files のサブフォルダ名 (nullなら直下)
 */
@Suppress("unused")
open class InteractiveMediaFileProvider(subFolder:String?=null): MediaFileProvider("", subFolder) {
    override suspend fun initialFileName(mimeType: String, inputFile: AndroidFile): String? {
        val initialName = super.initialFileName(mimeType, inputFile) ?: return null
//        val initialName = FileUtil.createInitialFileName(inputFile.getFileName() ?: "unnamed", outputFileSuffix, FileUtil.contentType2Ext(mimeType))
        return NameDialog.show(initialName)
    }
}

/**
 * あらかじめ設定した名前 (name) で Media Store に保存する FileProvider
 * @param name 保存先ファイル名（固定値）
 * @param subFolder Media Files のサブフォルダ名 (nullなら直下)
 */
open class NamedMediaFileProvider(val name:String, subFolder:String?=null): MediaFileProvider("", subFolder) {
    override suspend fun initialFileName(mimeType: String, inputFile: AndroidFile): String? {
        return name
    }
}

/**
 * 編集中ファイルを上書きする FileProvider
 * @param workSubFolder 一時ファイル作成場所指定。context.cacheDirの下にサブフォルダを作る場合はそのフォルダ名。nullなら cacheDir直下に配置
 */
open class OverwriteFileProvider(val showConfirmMessage:Boolean=true, val workSubFolder:String?=null) : IOutputFileProvider {
    protected open suspend fun getFallbackProvider(): IOutputFileProvider? {
        return ExportFileProvider("")
    }
    override suspend fun getOutputFile(mimeType:String, inputFile: AndroidFile): AndroidFile? {
        if (!inputFile.canWrite()) {
            AmeGlobal.logger.error("target file is not writable")
            return getFallbackProvider()?.getOutputFile(mimeType, inputFile)
        }
        if (showConfirmMessage) {
            val confirm = UtImmortalTask.awaitTaskResult(this::class.java.name) {
                showYesNoMessageBox("Overwrite", "Are you sure to overwrite the file?")
            }
            if (!confirm) return null
        }
        return FileUtil.createWorkFile(workSubFolder)
    }

    override suspend fun finalize(result: ISaveResult) {
        val outFile = result.outputFile as? AndroidFile ?: return
        val inputFile = result.inputFile as? AndroidFile ?: return
        try {
            if (result.succeeded) {
                inputFile.copyFrom(outFile)
            }
        } finally {
            outFile.safeDelete()
        }
    }
}

/**
 * 保存方法（上書き /名前を付けて保存/ Media Files にエクスポート）を選択させる FileProvider
 * @param outputFileSuffix inputFile から outputFile名を作成するときに付加するサフィックス
 * @param subFolder Media Files にエクスポートする場合は、そのサブフォルダ名 (nullなら直下)
 */
open class InteractiveOutputFileProvider(outputFileSuffix:String, val subFolder:String?) : AbstractNamedFileProvider(outputFileSuffix) {
    open fun getExportFileProvider(): IOutputFileProvider {
        return ExportFileProvider(outputFileSuffix)
    }
    open fun getNamedMediaFileProvider(targetName:String): IOutputFileProvider {
        return NamedMediaFileProvider(targetName, subFolder)
    }
    open fun getOverwriteFileProvider() : IOutputFileProvider{
        return OverwriteFileProvider(showConfirmMessage = false)    // 確認メッセージは SaveOptionDialog で表示済み
    }

    override suspend fun getOutputFile(mimeType: String, inputFile: AndroidFile): AndroidFile? {
        val initialName = initialFileName(mimeType, inputFile) ?: return null
        val option = SaveOptionDialog.show(initialName) ?: return null
        val provider = when(option.targetType) {
            SaveOptionDialog.SaveOptionViewModel.TargetType.OVERWRITE -> getOverwriteFileProvider()
            SaveOptionDialog.SaveOptionViewModel.TargetType.SAVE_MEDIA_FILE_AS -> getNamedMediaFileProvider(option.targetName)
            SaveOptionDialog.SaveOptionViewModel.TargetType.EXPORT_FILE -> getExportFileProvider()
        }
        return provider.getOutputFile(mimeType, inputFile)
    }
}

/**
 * 作業ファイル作成用 FileProvider
 * @param workSubFolder 一時ファイル作成場所指定。context.cacheDirの下にサブフォルダを作る場合はそのフォルダ名。nullなら cacheDir直下に配置
 */
@Suppress("unused")
class WorkFileProvider(val workSubFolder:String?=null) : IOutputFileProvider {
    override suspend fun getOutputFile(mimeType: String, inputFile: AndroidFile): AndroidFile {
        return FileUtil.createWorkFile(workSubFolder)
    }

    override suspend fun finalize(result: ISaveResult) {
        if (!result.succeeded) {
            result.outputFile?.safeDelete()
        }
    }
}

