package io.github.toyota32k.lib.media.editor.output

import io.github.toyota32k.dialog.broker.IUtActivityBrokerStoreProvider
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog
import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog.Companion.showProgressDialog
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.output.GenericSaveVideoTask.Companion.selectFile
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.IProgress
import io.github.toyota32k.media.lib.converter.format
import io.github.toyota32k.media.lib.converter.toAndroidFile

open class GenericSaveVideoTask(
    val videoStrategySelector: IVideoStrategySelector,
    val audioStrategySelector: IAudioStrategySelector,
    private val mKeepHdr: Boolean,
    override val fastStart: Boolean,
    private val outputFile: AndroidFile,
) : ISaveVideoTask, IProgressSink, IVideoStrategySelector by videoStrategySelector, IAudioStrategySelector by audioStrategySelector {
    val logger = UtLog("SaveVideoTask", AmeGlobal.logger)
    override suspend fun getOutputFile(): AndroidFile? {
        return outputFile
    }

    override val keepHdr: Boolean
        get() = if (videoStrategySelector is IVideoStrategyAndHdrSelector) {
                videoStrategySelector.keepHdr
            } else {
                mKeepHdr
            }


    var progressSink: ProgressDialog.ProgressSink? = null
    override suspend fun onStart(taskStatus: SaveTaskStatus, canceller: ICanceller?) {
        logger.debug(taskStatus.message)
        if (progressSink==null) {
            progressSink = showProgressDialog("Save File", canceller)
        }
    }

    override suspend fun onEnd(taskStatus: SaveTaskStatus, result: ISaveResult) {
        // nothing to do.
        logger.debug(taskStatus.message)
    }

    override suspend fun onFinished() {
        progressSink?.close()
        logger.debug()
    }

    override fun onProgress(status: SaveTaskStatus, progress: IProgress) {
        logger.debug("${status.message} ${progress.format()}")
        progressSink?.onProgress(status, progress)
    }

    companion object {
        fun getInitialFileName(orgName: String, ext:String): String {
            return if (orgName.isBlank()||orgName.lowercase()==ext) {
                "noname.mp4"
            } else if (orgName.lowercase().endsWith(ext)) {
                orgName
            } else {
                if (orgName.endsWith(".")) {
                    "$orgName${ext.substring(1)}"
                } else {
                    "$orgName.$ext"
                }
            }
        }

        suspend fun selectFile(type:String, baseName:String, ext:String):AndroidFile? {
            val owner = UtImmortalTaskManager.mortalInstanceSource.getOwner()
            val applicationContext = owner.application
            val pickerProvider = owner.lifecycleOwner as? IUtActivityBrokerStoreProvider ?: owner.asActivity() as? IUtActivityBrokerStoreProvider ?: return null
            val filePicker = pickerProvider.activityBrokers.createFilePicker
            return filePicker.selectFile(getInitialFileName(baseName, ext), type) ?.toAndroidFile(applicationContext)
        }

        suspend fun defaultTask(
            baseName: String,
            videoStrategySelector: IVideoStrategySelector,
            audioStrategySelector: IAudioStrategySelector,
            keepHdr: Boolean = true,
            fastStart: Boolean = true,
        ): ISaveVideoTask? {
            val outputFile = selectFile("video/mp4", baseName, ".mp4") ?: return null
            return GenericSaveVideoTask(videoStrategySelector, audioStrategySelector, keepHdr, fastStart, outputFile)
        }
    }

}

open class GenericSaveImageTask(val outputFile:AndroidFile): ISaveImageTask {
    override suspend fun getOutputFile(): AndroidFile? {
        return outputFile
    }

    override suspend fun onStart(taskStatus: SaveTaskStatus, canceller: ICanceller?) {
    }

    override suspend fun onEnd(taskStatus: SaveTaskStatus, result: ISaveResult) {
    }

    override suspend fun onFinished() {
    }

    companion object {
        suspend fun defaultTask(
            orgName: String,
            ): GenericSaveImageTask? {
            val outputFile = selectFile("image/jpeg", orgName, ".jpg") ?: return null
            return GenericSaveImageTask(outputFile)
        }

    }
}