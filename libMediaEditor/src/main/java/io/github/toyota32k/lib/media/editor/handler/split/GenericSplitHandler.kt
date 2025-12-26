package io.github.toyota32k.lib.media.editor.handler.split

import android.content.Context
import androidx.core.net.toUri
import io.github.toyota32k.lib.media.editor.handler.save.AbstractProgressSaveFileTask
import io.github.toyota32k.lib.media.editor.handler.save.CancelResult
import io.github.toyota32k.lib.media.editor.handler.save.IProgressSinkProvider
import io.github.toyota32k.lib.media.editor.handler.save.ISaveFileTask
import io.github.toyota32k.lib.media.editor.handler.save.SaveTaskListenerImpl
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.IMultiSplitResult
import io.github.toyota32k.lib.media.editor.model.IMultiOutputFileSelector
import io.github.toyota32k.lib.media.editor.model.ISourceInfo
import io.github.toyota32k.lib.media.editor.model.ISplitHandler
import io.github.toyota32k.lib.media.editor.model.IVideoSourceInfo
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel.VideoSourceInfoImpl.Companion.toRangeMsList
import io.github.toyota32k.media.lib.io.IInputMediaFile
import io.github.toyota32k.media.lib.io.toAndroidFile
import io.github.toyota32k.media.lib.processor.Processor
import io.github.toyota32k.media.lib.processor.ProcessorOptions
import io.github.toyota32k.media.lib.processor.contract.ICancellable
import io.github.toyota32k.media.lib.processor.contract.IConvertResult
import io.github.toyota32k.media.lib.processor.optimizer.OptimizerOptions
import io.github.toyota32k.media.lib.strategy.PresetAudioStrategies
import io.github.toyota32k.media.lib.strategy.PresetVideoStrategies
import io.github.toyota32k.media.lib.types.RangeMs
import io.github.toyota32k.media.lib.types.Rotation
import kotlinx.coroutines.flow.MutableStateFlow

abstract class AbstractSplitHandler(showSplitButton:Boolean) : ISplitHandler {
    val logger = AmeGlobal.logger
    override val showSplitButton = MutableStateFlow(showSplitButton)
    override val listener = SaveTaskListenerImpl<ISourceInfo, IMultiSplitResult>()
    companion object {
        const val MIN_RANGE = 100L // 100ms
    }
}

interface ISplitTask : ISaveFileTask, IProgressSinkProvider

class CancellerWrapper : ICancellable {
    private var outerCanceller: ICancellable? = null
    private var cancelled: Boolean = false
    fun setCanceller(canceller: ICancellable) {
        outerCanceller = canceller
        if (isCancelled) {
            canceller.cancel()
        }
    }

    val isCancelled: Boolean get() = cancelled
    override fun cancel() {
        cancelled = true
        outerCanceller?.cancel()
    }
}

class SplitTask : AbstractProgressSaveFileTask(), ISplitTask

/**
 * 現在の再生位置で分割する
 */
class GenericSplitHandler(
    context: Context,
    showSplitButton: Boolean,
    val startSplitTask:()->ISplitTask?={ SplitTask() }) : AbstractSplitHandler(showSplitButton) {
    val applicationContext = context.applicationContext ?: throw IllegalStateException("applicationContext is null")

    /**
     * 分割結果（複数ファイル）
     */
    class MultiResult : IMultiSplitResult {
        override val results: MutableList<IConvertResult> = mutableListOf()
        override val succeeded: Boolean get() = results.all { it.succeeded }
        override val cancelled: Boolean get() = results.any { it.cancelled }
        override val exception: Throwable? get() = results.firstOrNull { !it.cancelled && it.exception!=null }?.exception
        override val errorMessage: String? get() = results.firstOrNull { !it.cancelled && it.errorMessage!=null }?.errorMessage

        fun add(result: IConvertResult) = apply {
            results.add(result)
        }

        fun cancel(inputFile: IInputMediaFile?): MultiResult = apply {
            add(CancelResult(inputFile))
        }

        fun error(inputFile: IInputMediaFile?, e: Throwable, msg: String? = null): MultiResult = apply {
            add(Processor.ErrorResult(inputFile, e,msg))
        }
    }

    /**
     * 現在の再生位置で分割する
     * UI上で指定されたトリミングを反映した状態で、カーソル位置で２つに分割する。
     * 分割位置によっては、左側、あるいは、右側が空になる可能性があり、必ずしも２つのファイルが生成するとは限らない。
     */
    override suspend fun splitAtCurrentPosition(sourceInfo: IVideoSourceInfo, optimize:Boolean, fileSelector: IMultiOutputFileSelector): IMultiSplitResult {
        val task = startSplitTask() ?: return MultiResult().cancel(null)
        if (sourceInfo.positionMs<MIN_RANGE||sourceInfo.durationMs-MIN_RANGE < sourceInfo.durationMs) {
            logger.warn("requested range is too short.")
            return MultiResult().error(null, IllegalArgumentException("requested range is too short."))
        }
        val inFile = sourceInfo.source.uri.toUri().toAndroidFile(applicationContext)
        val ranges = mutableListOf(
            RangeMs(0, sourceInfo.positionMs),
            RangeMs(sourceInfo.positionMs, sourceInfo.durationMs)
        )
        if (!fileSelector.initialize(ranges)) {
            return MultiResult().cancel(inFile)
        }
        val processor = Processor()
        val processorOptionsBuilder = ProcessorOptions.Builder()
            .videoStrategy(PresetVideoStrategies.InvalidStrategy)
            .audioStrategy(PresetAudioStrategies.AACDefault)
            .input(inFile)
            .keepHDR(true)
            .keepVideoProfile(true)
            .trimming {
                addRangesMs(sourceInfo.trimmingRanges)
            }
            .rotate(Rotation.relative(sourceInfo.rotation))
            .crop(sourceInfo.cropRect)
            .brightness(sourceInfo.brightness)
        val optimizerOptions = OptimizerOptions(applicationContext) { progress->
            task.progressSink?.onProgress(progress)
        }
        val cancellerWrapper = CancellerWrapper()
        val multiResult = MultiResult()
        task.onStart(cancellerWrapper)
        listener.onSaveTaskStarted(sourceInfo)

        for (range in ranges) {
            val outFile = fileSelector.selectOutputFile(ranges.indexOf(range), range.startMs) ?: return multiResult.add(CancelResult(inFile))
            if (inFile == outFile) throw IllegalStateException("cannot overwrite input file on splitting file.")
            val processorOptions = processorOptionsBuilder
                .output(outFile)
                .clipStartMs(range.startMs)
                .clipEndMs(range.endMs)
                .build()
            cancellerWrapper.setCanceller(processor)
            val result = processor.execute(processorOptions, optimizerOptions)
            multiResult.add(result)
        }
        listener.onSaveTaskCompleted(multiResult)
        task.onEnd()
        fileSelector.finalize(multiResult)
        return multiResult
    }

    /**
     * トリミング範囲リスト（有効範囲のリスト）の各範囲毎（つまり、有効なチャプター毎）に分割する。
     * 有効範囲の数（sourceInfo.trimmingRanges.size)と出力ファイル数が一致する。
     */
    override suspend fun splitByChapters(sourceInfo: IVideoSourceInfo, optimize:Boolean, fileSelector: IMultiOutputFileSelector): IMultiSplitResult {
        val task = startSplitTask() ?: return MultiResult().cancel(null)
        val inFile = sourceInfo.source.uri.toUri().toAndroidFile(applicationContext)
        val ranges = sourceInfo.chapters.toRangeMsList(sourceInfo.durationMs)

        fileSelector.initialize(ranges)
        val processor = Processor()
        val processorOptionsBuilder = ProcessorOptions.Builder()
            .videoStrategy(PresetVideoStrategies.InvalidStrategy)
            .audioStrategy(PresetAudioStrategies.AACDefault)
            .input(inFile)
            .keepHDR(true)
            .keepVideoProfile(true)
            .rotate(Rotation.relative(sourceInfo.rotation))
            .crop(sourceInfo.cropRect)
            .brightness(sourceInfo.brightness)
        val optimizerOptions = OptimizerOptions(applicationContext) { progress->
            task.progressSink?.onProgress(progress)
        }

        val cancellerWrapper = CancellerWrapper()
        val multiResult = MultiResult()
        task.onStart(cancellerWrapper)
        listener.onSaveTaskStarted(sourceInfo)
        for (range in ranges) {
            val outFile = fileSelector.selectOutputFile(ranges.indexOf(range), range.startMs) ?: return multiResult.add(CancelResult(inFile))
            if (inFile == outFile) throw IllegalStateException("cannot overwrite input file on splitting file.")
            val processorOptions = processorOptionsBuilder
                .output(outFile)
                .trimming {
                    reset()
                    addRangeMs(range)
                }
                .build()
            cancellerWrapper.setCanceller(processor)
            val result = processor.execute(processorOptions, optimizerOptions)
            multiResult.add(result)
        }
        fileSelector.finalize(multiResult)
        listener.onSaveTaskCompleted(multiResult)
        task.onEnd()
        return multiResult
    }
}
