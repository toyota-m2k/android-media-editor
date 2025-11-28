package io.github.toyota32k.lib.media.editor.handler.split

import androidx.documentfile.provider.DocumentFile
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.lib.media.editor.dialog.NameDialog
import io.github.toyota32k.lib.media.editor.handler.ExportFileProvider
import io.github.toyota32k.lib.media.editor.handler.FileUtil
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.IOutputFileSelector
import io.github.toyota32k.media.lib.converter.IOutputMediaFile
import io.github.toyota32k.media.lib.converter.RangeMs
import java.lang.IllegalStateException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Splitter用 IOutputFileSelector の実装
 *
 * 作業フォルダ（cacheDir）にファイルを作成する IOutputFileSelector
 * 分割したファイルをさらに編集してから保存やエクスポートする方針なので、
 * 直接、保存・エクスポートするような selector は、当面不要かな。
 */
class OutputWorkFileSelector(subFolder:String?=null) : IOutputFileSelector {
    override suspend fun initialize(trimmedRangeMsList: List<RangeMs>): Boolean {
        return true
    }
    override suspend fun selectOutputFile(index: Int, positionMs: Long): IOutputMediaFile {
        return FileUtil.createWorkFile(null, "${index}-${positionMs}-", ".mp4")
    }
    override suspend fun terminate() {
    }
}

open class OneByOneExportFileSelector(val prefix:String="mov-", dateFormat: DateFormat?=null): IOutputFileSelector {
    var dateFormatForFilename = dateFormat ?: SimpleDateFormat("yyyy.MM.dd-HH:mm:ss", Locale.US)
    lateinit var baseName:String
    protected fun initBaseName():String {
        return "$prefix${dateFormatForFilename.format(Date())}".apply {
            baseName = this
        }
    }

    override suspend fun initialize(trimmedRangeMsList: List<RangeMs>): Boolean {
        initBaseName()
        return true
    }

    override suspend fun selectOutputFile(index: Int, positionMs: Long): IOutputMediaFile? {
        val initialName = "$baseName-${index+1}-${positionMs/1000L}"
        return FileUtil.selectFile("video/mp4", initialName)
    }

    override suspend fun terminate() {
    }
}

class ExportToDirectoryFileSelector(prefix: String="mov-", dateFormat: DateFormat?=null) : OneByOneExportFileSelector(prefix, dateFormat) {
    lateinit var targetFolder:DocumentFile
    override suspend fun initialize(trimmedRangeMsList: List<RangeMs>): Boolean {
        initBaseName()
        val name = NameDialog.show(baseName, "Base Name", "Name Prefix of Sequential Files") ?: return false
        this.baseName = name
        targetFolder = FileUtil.selectDirectory() ?: return false
        return true
    }
    override suspend fun selectOutputFile(index: Int, positionMs: Long): IOutputMediaFile? {
        val name = "$baseName-${index+1}-${positionMs/1000L}.mp4"
        val file = targetFolder.createFile("video/mp4", name)
        if (file==null) {
            AmeGlobal.logger.error("failed to create file: $name")
            return null
        }
        return AndroidFile(file.uri, UtImmortalTaskManager.mortalInstanceSource.getOwner().application)
    }
}