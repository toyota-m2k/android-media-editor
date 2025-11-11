package io.github.toyota32k.lib.media.editor.handler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.net.toUri
import io.github.toyota32k.lib.media.editor.dialog.NoReEncodeStrategy
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.IImageSourceInfo
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.lib.media.editor.model.ISaveFileHandler
import io.github.toyota32k.lib.media.editor.model.IVideoSourceInfo
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.logger.UtLog
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
import kotlinx.coroutines.CancellationException
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
    suspend fun onStart(taskStatus: SaveTaskStatus, canceller:ICanceller?)
    suspend fun onEnd(taskStatus: SaveTaskStatus, result: ISaveResult)
    suspend fun onFinished()
}

interface ISaveImageTask: ISaveFileTask {
    val imageFormat get() = Bitmap.CompressFormat.JPEG
    val quality get() = 100
}

interface IVideoStrategySelector {
    suspend fun getVideoStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IVideoStrategy?
}
interface IVideoStrategyAndHdrSelector : IVideoStrategySelector {
    val keepHdr: Boolean
}

interface IAudioStrategySelector {
    suspend fun getAudioStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IAudioStrategy?
}

object DefaultAudioStrategySelector: IAudioStrategySelector {
    override suspend fun getAudioStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IAudioStrategy? {
        return PresetAudioStrategies.AACDefault
    }
}

class SingleVideoStrategySelector(val strategy:IVideoStrategy): IVideoStrategySelector {
    override suspend fun getVideoStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IVideoStrategy? {
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

    override suspend fun saveImage(sourceInfo: IImageSourceInfo, outputFileProvider: IOutputFileProvider): Boolean {
        val task = startSaveTask(TaskKind.SAVE_IMAGE) ?: return false
        val (imageFormat, quality) = if (task is ISaveImageTask) {
            task.imageFormat to task.quality
        } else (Bitmap.CompressFormat.JPEG to 100)
        val mimeType = when(imageFormat) {
            Bitmap.CompressFormat.JPEG -> "image/jpeg"
            Bitmap.CompressFormat.PNG -> "image/png"
            else -> "image/*"
        }
        val inputFile = sourceInfo.source.uri.toUri().toAndroidFile(applicationContext)
        val outputFile = outputFileProvider.getOutputFile(mimeType, inputFile) ?: return false
        try {
            task.onStart(SaveTaskStatus.FINALIZING, null)  // image does not support cancellation
            outputFile.fileOutputStream { outputStream ->
                sourceInfo.editedBitmap.compress(imageFormat, quality, outputStream)
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

    override suspend fun saveVideo(sourceInfo: IVideoSourceInfo, outputFileProvider: IOutputFileProvider): Boolean {
        val source = sourceInfo.source
        val task = startSaveTask(TaskKind.SAVE_VIDEO) as? ISaveVideoTask ?: return false
        val inputFile = source.uri.toUri().toAndroidFile(applicationContext)
        val videoStrategy = task.getVideoStrategy(inputFile, sourceInfo) ?: return false
        val audioStrategy = task.getAudioStrategy(inputFile, sourceInfo) ?: return false
        val noReEncoding = videoStrategy== NoReEncodeStrategy || (sourceInfo.cropRect == null && sourceInfo.brightness == null && !Converter.checkReEncodingNecessity(inputFile, videoStrategy))

        return WorkFileMediator(applicationContext, outputFileProvider, inputFile).use { mediator ->
            try {
                // stage1: Convert/Trimming
                val stage1 = mediator.firstStage { inFile, outFile ->
                    val trimmingRanges = sourceInfo.trimmingRanges
                    // Trimming and Conversion
                    val result = if (noReEncoding) {
                        if (trimmingRanges.isEmpty()) {
                            logger.warn("maybe no effect")
                        }
                        trimmingWithNoReEncoding(task, inFile, outFile, sourceInfo)
                    } else {
                        trimmingAndConvert(task, videoStrategy, audioStrategy, inFile, outFile, sourceInfo)
                    }
                    task.onEnd(SaveTaskStatus.CONVERTING, result)
                    result.succeeded
                }
                if (!stage1) return false
                if (task.fastStart) {
                    // stage2: fast start
                    val stage2 = mediator.lastStage { inFile, outFile ->
                        task.onStart(SaveTaskStatus.FAST_STARTING, null)
                        FastStart.process(inFile, outFile, true) { progress ->
                            (task as? IProgressSink)?.onProgress(SaveTaskStatus.FAST_STARTING, progress)
                        }
                    }
                    if (!stage2) return false
                }
                mediator.finalize()
                return true
            } catch(e:Throwable) {
                if (e !is CancellationException) {
                    logger.error(e)
                }
                false
            } finally {
                task.onFinished()
            }
        }
    }

    private suspend fun trimmingAndConvert(task:ISaveFileTask, videoStrategy: IVideoStrategy, audioStrategy:IAudioStrategy, inputFile: IInputMediaFile, outputFile: IOutputMediaFile, sourceInfo: IVideoSourceInfo): ISaveVideoTask.SaveVideoResult {
        return try {
            val videoTask = task as? ISaveVideoTask
            val trimmingRanges = sourceInfo.trimmingRanges
            val rotation: Int = sourceInfo.rotation
            val cropRect: Rect? = sourceInfo.cropRect
            val brightness:Float? = sourceInfo.brightness

            val converter = Converter.factory
                .input(inputFile)
                .output(outputFile)
                .videoStrategy(videoStrategy)
                .audioStrategy(audioStrategy)
                .keepHDR(videoTask?.keepHdr ?: true)
                .apply {
                    if(trimmingRanges.isNotEmpty()) {
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

    private suspend fun trimmingWithNoReEncoding(task:ISaveFileTask, inputFile: IInputMediaFile, outputFile: IOutputMediaFile, sourceInfo: IVideoSourceInfo): ISaveVideoTask.SaveVideoResult {
        return try {
            val trimmingRanges = sourceInfo.trimmingRanges
            val rotation: Int = sourceInfo.rotation
//            val cropRect: Rect? = sourceInfo.cropRect
//            val brightness:Float? = sourceInfo.brightness
            val splitter = Splitter.Factory()
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
            splitter.trim(inputFile,outputFile, *trimmingRanges).let { tr->
                val adjustedRanges = if (trimmingRanges.isNotEmpty()) splitter.adjustedRangeList(trimmingRanges) else null
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
                when (taskKind) {
                    TaskKind.SAVE_IMAGE -> GenericSaveImageTask.defaultTask()
                    TaskKind.SAVE_VIDEO -> GenericSaveVideoTask.defaultTask(videoStrategySelector, audioStrategySelector)
                }
            }
        }
    }
}

