package io.github.toyota32k.lib.media.editor.handler.split

import androidx.documentfile.provider.DocumentFile
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.lib.media.editor.dialog.NameDialog
import io.github.toyota32k.lib.media.editor.handler.FileUtil
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.IOutputFileSelector
import io.github.toyota32k.media.lib.converter.IOutputMediaFile
import io.github.toyota32k.media.lib.converter.RangeMs
import io.github.toyota32k.utils.TimeSpan
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Splitter用 IOutputFileSelector の実装
 *
 * 作業フォルダ（cacheDir）にファイルを作成する IOutputFileSelector
 */
class OutputWorkFileSelector(subFolder:String?=null) : IOutputFileSelector {
    override suspend fun initialize(trimmedRangeMsList: List<RangeMs>): Boolean {
        return true
    }
    override suspend fun selectOutputFile(index: Int, positionMs: Long): IOutputMediaFile {
        val time = if (positionMs>0) TimeSpan(positionMs).run { if(hours>0) "$hours.$minutes.$seconds}" else "$minutes.$seconds"} else ""
        return FileUtil.createWorkFile(null, "${index}-${time}-", ".mp4")
    }
    override suspend fun terminate() {
    }
}

open class OneByOneExportFileSelector(val prefix:String="mov-", dateFormat: DateFormat?=null): IOutputFileSelector {
    var dateFormatForFilename = dateFormat ?: SimpleDateFormat("yyyy.MM.dd-HH:mm:ss", Locale.US)

    protected var presetBaseName:String? = null
    open fun getBaseName():String {
        val name = presetBaseName
        return if (name.isNullOrBlank()) {
            "$prefix${dateFormatForFilename.format(Date())}".apply { presetBaseName = this }
        } else name
    }

    override suspend fun initialize(trimmedRangeMsList: List<RangeMs>): Boolean {
        getBaseName()
        return true
    }

    override suspend fun selectOutputFile(index: Int, positionMs: Long): IOutputMediaFile? {
        val time = if (positionMs>0) TimeSpan(positionMs).run { if(hours>0) "$hours.$minutes.$seconds}" else "$minutes.$seconds"} else ""
        val initialName = "${getBaseName()}-${index+1}-$time"
        return FileUtil.selectFile("video/mp4", initialName)
    }

    override suspend fun terminate() {
    }
}

open class ExportToDirectoryFileSelector(prefix: String="mov-", dateFormat: DateFormat?=null) : OneByOneExportFileSelector(prefix, dateFormat) {
    lateinit var targetFolder:DocumentFile
    override suspend fun initialize(trimmedRangeMsList: List<RangeMs>): Boolean {
        val baseName = getBaseName()
        val name = NameDialog.show(baseName, "Base Name", "Name Prefix of Sequential Files") ?: return false
        this.presetBaseName = name
        targetFolder = FileUtil.selectDirectory() ?: return false
        return true
    }
    override suspend fun selectOutputFile(index: Int, positionMs: Long): IOutputMediaFile? {
        val name = "${getBaseName()}-${index+1}-${positionMs/1000L}.mp4"
        val file = targetFolder.createFile("video/mp4", name)
        if (file==null) {
            AmeGlobal.logger.error("failed to create file: $name")
            return null
        }
        return AndroidFile(file.uri, UtImmortalTaskManager.mortalInstanceSource.getOwner().application)
    }
}