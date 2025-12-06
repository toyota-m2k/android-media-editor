package io.github.toyota32k.lib.media.editor.handler.save

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import io.github.toyota32k.lib.media.editor.handler.AndroidMediaFile.saveBitmap
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.IImageSourceInfo
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.lib.media.editor.model.ISaveFileHandler
import io.github.toyota32k.lib.media.editor.model.ISourceInfo
import io.github.toyota32k.lib.media.editor.model.IVideoSourceInfo
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.lib.converter.ConvertResult
import io.github.toyota32k.media.lib.converter.ICancellable
import io.github.toyota32k.media.lib.converter.IConvertResult
import io.github.toyota32k.media.lib.converter.IInputMediaFile
import io.github.toyota32k.media.lib.converter.IMultiPhaseProgress
import io.github.toyota32k.media.lib.converter.IOutputMediaFile
import io.github.toyota32k.media.lib.converter.Rotation
import io.github.toyota32k.media.lib.converter.TrimOptimizer
import io.github.toyota32k.media.lib.converter.toAndroidFile
import io.github.toyota32k.media.lib.report.Report
import io.github.toyota32k.media.lib.strategy.IAudioStrategy
import io.github.toyota32k.media.lib.strategy.IVideoStrategy
import io.github.toyota32k.media.lib.strategy.PresetAudioStrategies
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * cancel i/f
 */
//fun interface ICanceller {
//    fun cancel()
//}

///**
// * 保存タスクのステータス
// */
//enum class SaveTaskStatus(val message:String, val phase: IMultiPhaseProgress.Phase?){
//    CONVERTING("Converting", IMultiPhaseProgress.Phase.CONVERTING),
//    EXTRACTING("Extracting", IMultiPhaseProgress.Phase.OPTIMIZING),
//    OPTIMIZING("Optimizing", IMultiPhaseProgress.Phase.OPTIMIZING),
//    FINALIZING("Finalizing", null),
//    ;
//    companion object {
//        fun fromPhase(phase: IMultiPhaseProgress.Phase?): SaveTaskStatus {
//            return entries.firstOrNull { it.phase == phase  } ?: CONVERTING
//        }
//    }
//}

interface IProgressSink {
    fun onProgress(progress: IMultiPhaseProgress)
    fun complete()
}

/**
 * 保存結果を返す i/f
 */
interface ISaveResult {
    enum class Status {
        SUCCESS,
        ERROR,
        CANCELLED
    }
    val sourceInfo: ISourceInfo
    val status: Status
    val error:Throwable?
    val errorMessage:String?

    val succeeded:Boolean get() = status == Status.SUCCESS
    val cancelled:Boolean get() = status == Status.CANCELLED
    val failed:Boolean get() = status == Status.ERROR

    val outputFile: IOutputMediaFile?
}

/**
 * 画像保存結果
 */
class ImageSaveResult(override val outputFile: IOutputMediaFile?, override val status:ISaveResult.Status, override val sourceInfo:ISourceInfo, override val error:Throwable?, override val errorMessage:String?): ISaveResult {
    companion object {
        fun succeeded(sourceInfo:ISourceInfo,outputFile: IOutputMediaFile):ImageSaveResult = ImageSaveResult(outputFile,ISaveResult.Status.SUCCESS, sourceInfo,null, null)
        fun cancelled(sourceInfo:ISourceInfo): ImageSaveResult = ImageSaveResult(null, ISaveResult.Status.CANCELLED, sourceInfo,null, null)
        fun error(sourceInfo:ISourceInfo, error:Throwable, message:String? = null) = ImageSaveResult(null,ISaveResult.Status.ERROR, sourceInfo,error, message)
    }
}

/**
 * ファイル保存処理の基本タスク i/f
 */
interface ISaveFileTask {
    suspend fun onStart(canceller: ICancellable?)
    suspend fun onEnd()
//    suspend fun onFinished()
}

/**
 * 画像保存処理のタスク i/f
 */
interface ISaveImageTask: ISaveFileTask {
    val imageFormat get() = Bitmap.CompressFormat.JPEG
    val quality get() = 100
}

/**
 * IVideoStrategy 選択用 i/f
 */
interface IVideoStrategySelector {
    suspend fun getVideoStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IVideoStrategy?
}
/**
 * IVideoStrategy + keepHdrフラグ選択用 i/f
 */
interface IVideoStrategyAndHdrSelector : IVideoStrategySelector {
    val keepHdr: Boolean
}

/**
 * IAudioStrategy 選択用 i/f
 */
interface IAudioStrategySelector {
    suspend fun getAudioStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IAudioStrategy?
}

/**
 * AAC専用 IAudioStrategySelector
 * ...どのみち AACくらいしかサポートしていない。
 */
object DefaultAudioStrategySelector: IAudioStrategySelector {
    override suspend fun getAudioStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IAudioStrategy {
        return PresetAudioStrategies.AACDefault
    }
}

/**
 * 単一 IVideoStrategy を使用する IVideoStrategySelector
 */
class SingleVideoStrategySelector(val strategy:IVideoStrategy): IVideoStrategySelector {
    override suspend fun getVideoStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IVideoStrategy {
        return strategy
    }
}

interface IProgressSinkProvider {
    val progressSink: IProgressSink?
}

/**
 * 動画のエンコード処理に必要なパラメータ（コーデック、keepHdr）を保持・供給し、
 * プログレス表示を担当するタスクの i/f
 */
interface ISaveVideoTask: ISaveFileTask, IProgressSinkProvider, IVideoStrategySelector, IAudioStrategySelector {
    val keepHdr: Boolean
    val fastStart: Boolean
}

/**
 * ISaveFileHandlerの汎用実装クラス
 * 
 * Image/Video共通
 * getImageSaveTask, getVideoSaveTask を設定することによって動作をカスタマイズできる。
 */
open class GenericSaveFileHandler(
    context: Context,
    showSaveButton:Boolean,
    val startImageSaveTask: (() -> ISaveImageTask?) = { GenericSaveImageTask.defaultTask() },
    val startVideoSaveTask: (() -> ISaveVideoTask?) = { GenericSaveVideoTask.defaultTask(InteractiveVideoStrategySelector(), DefaultAudioStrategySelector) }
) : ISaveFileHandler {
    val logger = UtLog("SaveFileHandler", AmeGlobal.logger)
    val applicationContext = context.applicationContext ?: throw IllegalStateException("applicationContext is null")
    override val showSaveButton = MutableStateFlow(showSaveButton)
    override val listener = SaveTaskListenerImpl<ISourceInfo, ISaveResult>()
    enum class TaskKind {
        SAVE_IMAGE,
        SAVE_VIDEO,
    }

    class SaveVideoResult private constructor (override val sourceInfo:ISourceInfo, val convertResult: IConvertResult): ISaveResult {
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

        override val outputFile: IOutputMediaFile?
            get() = convertResult.outputFile

        companion object {
//            fun error(error:Throwable, message:String? = null) = SaveVideoResult( null, ConvertResult.error(error, message))
            fun cancel(sourceInfo:ISourceInfo) = SaveVideoResult(sourceInfo, ConvertResult.cancelled)
            fun fromResult(sourceInfo:ISourceInfo, result: IConvertResult) = SaveVideoResult(sourceInfo,result)
        }
    }



    /**
     * 画像ファイルを保存する
     */
    override suspend fun saveImage(sourceInfo: IImageSourceInfo, outputFileProvider: IOutputFileProvider): Boolean {
        val task = startImageSaveTask() ?: return false
        val imageFormat = task.imageFormat
        val quality = task.quality
        val mimeType = when(imageFormat) {
            Bitmap.CompressFormat.JPEG -> "image/jpeg"
            Bitmap.CompressFormat.PNG -> "image/png"
            else -> "image/*"
        }
        val inputFile = sourceInfo.source.uri.toUri().toAndroidFile(applicationContext)
        val outputFile = outputFileProvider.getOutputFile(mimeType, inputFile) ?: return false
        val result = try {
            task.onStart(null)  // image does not support cancellation
            listener.onSaveTaskStarted(sourceInfo)
            outputFile.saveBitmap(sourceInfo.editedBitmap, imageFormat, quality)
            ImageSaveResult.succeeded(sourceInfo,outputFile)
        } catch(e:Throwable) {
            logger.error(e)
            ImageSaveResult.error(sourceInfo,e)
        }
        outputFileProvider.finalize(result.succeeded, inputFile, outputFile)
        listener.onSaveTaskCompleted(result)
        task.onEnd()
        return result.succeeded
    }

    /**
     * 動画ファイルを保存する
     */
    override suspend fun saveVideo(sourceInfo: IVideoSourceInfo, outputFileProvider: IOutputFileProvider): Boolean {
        val source = sourceInfo.source
        val task = startVideoSaveTask() ?: return false
        val inputFile = source.uri.toUri().toAndroidFile(applicationContext)
        val videoStrategy = task.getVideoStrategy(inputFile, sourceInfo) ?: return false
        val audioStrategy = task.getAudioStrategy(inputFile, sourceInfo) ?: return false
        val inFile = source.uri.toUri().toAndroidFile(applicationContext)
        val outFile = outputFileProvider.getOutputFile("video/mp4", inFile) ?: return false

        val trimOptimizer = TrimOptimizer.Builder(applicationContext)
            .input(inFile)
            .output(outFile)
            .deleteOutputOnError(true)
            .videoStrategy(videoStrategy)
            .audioStrategy(audioStrategy)
            .keepHDR(task.keepHdr)
            .fastStart(true)
            .removeFreeOnFastStart(true)
            .trimming {
                addRangesMs(sourceInfo.trimmingRanges)
            }
            .rotate(Rotation.relative(sourceInfo.rotation))
            .crop(sourceInfo.cropRect)
            .brightness(sourceInfo.brightness)
            .setProgressHandler { progress->
                task.progressSink?.onProgress(progress)
            }
            .build()

        task.onStart(trimOptimizer)
        listener.onSaveTaskStarted(sourceInfo)
        val result = trimOptimizer.execute()
        outputFileProvider.finalize(result.succeeded, inFile, outFile)
        listener.onSaveTaskCompleted(SaveVideoResult.fromResult(sourceInfo,result))
        task.onEnd()
        return result.succeeded
    }
}

