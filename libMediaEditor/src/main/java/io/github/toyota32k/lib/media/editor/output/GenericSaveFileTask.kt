package io.github.toyota32k.lib.media.editor.output

import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog
import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog.Companion.showProgressDialog
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.lib.converter.IProgress
import io.github.toyota32k.media.lib.converter.format

open class GenericSaveVideoTask(
    val videoStrategySelector: IVideoStrategySelector,
    val audioStrategySelector: IAudioStrategySelector,
    private val mKeepHdr: Boolean,
    override val fastStart: Boolean,
) : ISaveVideoTask, IProgressSink, IVideoStrategySelector by videoStrategySelector, IAudioStrategySelector by audioStrategySelector {
    val logger = UtLog("SaveVideoTask", AmeGlobal.logger)

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
        fun defaultTask(
            videoStrategySelector: IVideoStrategySelector,
            audioStrategySelector: IAudioStrategySelector,
            keepHdr: Boolean = true,
            fastStart: Boolean = true,
        ): ISaveVideoTask {
//            val outputFile = selectFile("video/mp4", baseName, ".mp4") ?: return null
            return GenericSaveVideoTask(videoStrategySelector, audioStrategySelector, keepHdr, fastStart)
        }
    }
}

class GenericSaveImageTask : ISaveImageTask {
    override suspend fun onStart(taskStatus: SaveTaskStatus, canceller: ICanceller?) {
    }

    override suspend fun onEnd(taskStatus: SaveTaskStatus, result: ISaveResult) {
    }

    override suspend fun onFinished() {
    }
    companion object {
        fun defaultTask(): ISaveImageTask {
            return GenericSaveImageTask()
        }
    }
}
