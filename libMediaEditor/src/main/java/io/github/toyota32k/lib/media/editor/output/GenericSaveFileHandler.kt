package io.github.toyota32k.lib.media.editor.output

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.core.net.toUri
import io.github.toyota32k.dialog.broker.pickers.UtCreateFilePicker
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.ISaveFileHandler
import io.github.toyota32k.lib.player.common.TpTempFile
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.ConvertResult
import io.github.toyota32k.media.lib.converter.Converter
import io.github.toyota32k.media.lib.converter.FastStart
import io.github.toyota32k.media.lib.converter.IInputMediaFile
import io.github.toyota32k.media.lib.converter.IOutputMediaFile
import io.github.toyota32k.media.lib.converter.IProgress
import io.github.toyota32k.media.lib.converter.Rotation
import io.github.toyota32k.media.lib.converter.Splitter
import io.github.toyota32k.media.lib.converter.toAndroidFile
import io.github.toyota32k.media.lib.report.Report
import io.github.toyota32k.media.lib.strategy.IAudioStrategy
import io.github.toyota32k.media.lib.strategy.IVideoStrategy
import io.github.toyota32k.media.lib.strategy.PresetAudioStrategies
import kotlinx.coroutines.flow.MutableStateFlow

abstract class AbstractSaveFileHandler(showSaveButton:Boolean) : ISaveFileHandler {
    override val showSaveButton = MutableStateFlow(showSaveButton)
}

fun interface ICanceller {
    fun cancel()
}

enum class SaveTaskStatus(val message:String){
    CONVERTING("Converting"),
    FAST_STARTING("Optimizing"),
    FINALIZING("Finalizing"),
}

interface IProgressSink {
    fun onProgress(status:SaveTaskStatus, progress:IProgress)
}

interface ISaveResult {
    enum class Status {
        SUCCESS,
        ERROR,
        CANCELLED
    }
    val status: Status
    val error:Throwable?
    val errorMessage:String?

    val succeeded:Boolean get() = status == Status.SUCCESS
    val cancelled:Boolean get() = status == Status.CANCELLED
    val failed:Boolean get() = status == Status.ERROR
}

class ImageSaveResult(override val status:ISaveResult.Status, override val error:Throwable?, override val errorMessage:String?): ISaveResult {
    companion object {
        val succeeded:ImageSaveResult = ImageSaveResult(ISaveResult.Status.SUCCESS, null, null)
        val cancelled: ImageSaveResult = ImageSaveResult(ISaveResult.Status.CANCELLED, null, null)
        fun error(error:Throwable, message:String? = null) = ImageSaveResult(ISaveResult.Status.ERROR, error, message)
    }
}

interface ISaveFileTask {
    suspend fun getOutputFile(): AndroidFile?
    suspend fun onStart(taskStatus: SaveTaskStatus, canceller:ICanceller?)
    suspend fun onEnd(taskStatus: SaveTaskStatus, result: ISaveResult)
    suspend fun onFinished()
}

interface ISaveImageTask: ISaveFileTask {
    val imageFormat get() = Bitmap.CompressFormat.JPEG
    val quality get() = 100
}

interface IVideoStrategySelector {
    suspend fun getVideoStrategy(inputFile: IInputMediaFile, currentPositionMs:Long, trimmingRanges:Array<Converter.Factory.RangeMs>?): IVideoStrategy?
}
interface IAudioStrategySelector {
    suspend fun getAudioStrategy(inputFile: IInputMediaFile, currentPositionMs:Long, trimmingRanges:Array<Converter.Factory.RangeMs>?): IAudioStrategy?
}

object DefaultAudioStrategySelector: IAudioStrategySelector {
    override suspend fun getAudioStrategy(inputFile: IInputMediaFile, currentPositionMs: Long, trimmingRanges: Array<Converter.Factory.RangeMs>?): IAudioStrategy? {
        return PresetAudioStrategies.AACDefault
    }
}

class SingleVideoStrategySelector(val strategy:IVideoStrategy): IVideoStrategySelector {
    suspend override fun getVideoStrategy(inputFile: IInputMediaFile, currentPositionMs:Long, trimmingRanges:Array<Converter.Factory.RangeMs>?): IVideoStrategy? {
        return strategy
    }
}

interface ISaveVideoTask: ISaveFileTask, IProgressSink, IVideoStrategySelector, IAudioStrategySelector {
    val keepHdr: Boolean
    val fastStart: Boolean
    class SaveVideoResult(val convertResult: ConvertResult): ISaveResult {
        override val status: ISaveResult.Status
            get() = when {
                convertResult.succeeded -> ISaveResult.Status.SUCCESS
                convertResult.cancelled -> ISaveResult.Status.CANCELLED
                else -> ISaveResult.Status.ERROR
            }
        override val error: Throwable?
            get() = convertResult.exception
        override val errorMessage: String?
            get() = convertResult.errorMessage

        val report:Report?
            get() = convertResult.report

        companion object {
            fun fatal(error:Throwable, message:String? = null) = SaveVideoResult(ConvertResult.error(error, message))
        }
    }
}

class GenericSaveFileHandler(
    showSaveButton:Boolean,
    val applicationContext: Context,
    val playerControllerModel: PlayerControllerModel,
    val startSaveTask:suspend (taskKind:TaskKind)-> ISaveFileTask?
) : AbstractSaveFileHandler(showSaveButton) {
    val logger = UtLog("SaveFileHandler", AmeGlobal.logger)
    enum class TaskKind {
        SAVE_IMAGE,
        SAVE_VIDEO,
    }

    override suspend fun saveImage(newBitmap: Bitmap): Boolean {
        val task = startSaveTask(TaskKind.SAVE_IMAGE) ?: return false
        val file = task.getOutputFile() ?: return false
        try {
            task.onStart(SaveTaskStatus.FINALIZING, null)  // image does not support cancellation
            val (imageFormat, quality) = if (task is ISaveImageTask) {
                task.imageFormat to task.quality
            } else (Bitmap.CompressFormat.JPEG to 100)
            file.fileOutputStream { outputStream ->
                newBitmap.compress(imageFormat, quality, outputStream)
                outputStream.flush()
            }
            task.onEnd(SaveTaskStatus.FINALIZING, ImageSaveResult.succeeded)
            return true
        } catch(e:Throwable) {
            logger.error(e)
            task.onEnd(SaveTaskStatus.FINALIZING, ImageSaveResult.error(e))
            return false
        }
    }

    override suspend fun saveVideo(trimmingRanges: Array<Converter.Factory.RangeMs>?, rotation: Int, cropRect: Rect?, brightness: Float?): Boolean {
        val source = playerControllerModel.playerModel.currentSource.value ?: return false
        val task = startSaveTask(TaskKind.SAVE_VIDEO) as? ISaveVideoTask ?: return false
        val position = playerControllerModel.playerModel.currentPosition
        val inputFile = source.uri.toUri().toAndroidFile(applicationContext)
        val videoStrategy = task.getVideoStrategy(inputFile, position, trimmingRanges) ?: return false
        val audioStrategy = task.getAudioStrategy(inputFile, position, trimmingRanges) ?: return false

        try {
            val outputFile = task.getOutputFile() ?: return false
            if (task.fastStart) {
                return convertAndOptimize(task, videoStrategy, audioStrategy, inputFile, outputFile, trimmingRanges, rotation, cropRect, brightness)
            } else {
                return convertOnly(task, videoStrategy, audioStrategy, outputFile, inputFile, trimmingRanges, rotation, cropRect, brightness)
            }
        } finally {
            task.onFinished()
        }
    }

    private suspend fun convertOnly(task:ISaveVideoTask, videoStrategy: IVideoStrategy, audioStrategy:IAudioStrategy, outputFile: AndroidFile, inputFile: IInputMediaFile, trimmingRanges: Array<Converter.Factory.RangeMs>?, rotation: Int, cropRect: Rect?, brightness: Float?):Boolean {
        return try {
            // Trimming and Conversion
            val result = if (cropRect == null && brightness == null && !Converter.checkReEncodingNecessity(inputFile, videoStrategy)) {
                if (trimmingRanges == null) {
                    logger.warn("maybe no effect")
                }
                trimmingWithNoReEncoding(task, inputFile, outputFile, trimmingRanges, rotation)
            } else {
                trimmingAndConvert(task, videoStrategy, audioStrategy, inputFile, outputFile, trimmingRanges, rotation, cropRect, brightness)
            }
            task.onEnd(SaveTaskStatus.CONVERTING, result)
            result.succeeded
        }
        catch(e:Throwable) {
            logger.error(e)
            outputFile.safeDelete()
            return false
        }
    }

    private suspend fun convertAndOptimize(task: ISaveFileTask, videoStrategy: IVideoStrategy, audioStrategy:IAudioStrategy, inputFile: IInputMediaFile, outputFile:AndroidFile, trimmingRanges: Array<Converter.Factory.RangeMs>?, rotation: Int, cropRect: Rect?, brightness: Float?):Boolean {
        TpTempFile(applicationContext, "ame-", ".mp4").use { tempFile ->
            var converted:Boolean = false
            val intermediateFile: AndroidFile = tempFile.file.toAndroidFile()
            val videoTask = task as? ISaveVideoTask
            try {
                // Trimming and Conversion
                val result = if (cropRect == null && brightness == null && !Converter.checkReEncodingNecessity(inputFile, videoStrategy)) {
                    if (trimmingRanges == null) {
                        logger.warn("maybe no effect")
                    }
                    trimmingWithNoReEncoding(task, inputFile, intermediateFile, trimmingRanges, rotation)
                } else {
                    trimmingAndConvert(task, videoStrategy, audioStrategy, inputFile, intermediateFile, trimmingRanges, rotation, cropRect, brightness)
                }
                task.onEnd(SaveTaskStatus.CONVERTING, result)
                if (!result.succeeded) {
                    return false
                }

                task.onStart(SaveTaskStatus.FAST_STARTING, null)
                converted = FastStart.process(intermediateFile, outputFile, true) { progress->
                    (task as? IProgressSink)?.onProgress(SaveTaskStatus.FAST_STARTING, progress)
                }
                return converted
            } catch (e: Throwable) {
                logger.error(e)
                return false
            } finally {
                if (!converted) {
                    outputFile.safeDelete()
                }
            }
        }
    }

    private suspend fun trimmingAndConvert(task:ISaveFileTask, videoStrategy: IVideoStrategy, audioStrategy:IAudioStrategy, inputFile: IInputMediaFile, outputFile: IOutputMediaFile, trimmingRanges: Array<Converter.Factory.RangeMs>?, rotation: Int, cropRect: Rect?, brightness: Float?): ISaveVideoTask.SaveVideoResult {
        return try {
            val videoTask = task as? ISaveVideoTask
            val converter = Converter.factory
                .input(inputFile)
                .output(outputFile)
                .videoStrategy(videoStrategy)
                .audioStrategy(audioStrategy)
                .keepHDR(videoTask?.keepHdr ?: true)
                .apply {
                    if(trimmingRanges!=null) {
                        addTrimmingRanges(*trimmingRanges)
                    }
                    if (rotation!=0) {
                        rotate(Rotation(rotation, true))
                    }
                    if (cropRect!=null) {
                        crop(cropRect)
                    }
                    if (brightness!=null) {
                        this.brightness(brightness)
                    }
                }
                .setProgressHandler { progress->
                    (task as? IProgressSink)?.onProgress(SaveTaskStatus.CONVERTING, progress)
                }
                .build()
            task.onStart(SaveTaskStatus.CONVERTING) {
                converter.cancel()
            }
            converter.execute().let { cr->
                ISaveVideoTask.SaveVideoResult(cr)
            }
        } catch(e:Throwable) {
            logger.error(e)
            ISaveVideoTask.SaveVideoResult.fatal(e)
        }
    }

    private suspend fun trimmingWithNoReEncoding(task:ISaveFileTask, inputFile: IInputMediaFile, outputFile: IOutputMediaFile, trimmingRanges: Array<Converter.Factory.RangeMs>?, rotation: Int): ISaveVideoTask.SaveVideoResult {
        return try {
            val videoTask = task as? ISaveVideoTask
            val splitter = Splitter.Factory(inputFile)
                .apply {
                    if (rotation!=0) {
                        rotate(Rotation(rotation, true))
                    }
                }
                .setProgressHandler { progress->
                    (task as? IProgressSink)?.onProgress(SaveTaskStatus.CONVERTING, progress)
                }
                .build()
            task.onStart(SaveTaskStatus.CONVERTING) {
                splitter.cancel()
            }
            splitter.trim(outputFile, *(trimmingRanges?:emptyArray())).let { tr->
                val adjustedRanges = if (trimmingRanges!=null) splitter.adjustedRangeList(trimmingRanges) else null
                ISaveVideoTask.SaveVideoResult(ConvertResult(tr.succeeded, adjustedRanges, report=null, tr.cancelled, null, tr.error))
            }
        } catch(e:Throwable) {
            logger.error(e)
            ISaveVideoTask.SaveVideoResult.fatal(e)
        }
    }

    companion object {
        fun create(
            showSaveButton:Boolean,
            applicationContext: Context,
            playerControllerModel: PlayerControllerModel,
            videoStrategySelector: IVideoStrategySelector,
            audioStrategySelector: IAudioStrategySelector = DefaultAudioStrategySelector,
            ): GenericSaveFileHandler {
            return GenericSaveFileHandler(showSaveButton, applicationContext, playerControllerModel) { taskKind ->
                val source = playerControllerModel.playerModel.currentSource.value ?: return@GenericSaveFileHandler null
                when (taskKind) {
                    TaskKind.SAVE_IMAGE -> GenericSaveImageTask.defaultTask( source.name)
                    TaskKind.SAVE_VIDEO -> GenericSaveVideoTask.defaultTask( source.name, videoStrategySelector, audioStrategySelector)
//                    else -> null
                }
            }
        }
    }
}

