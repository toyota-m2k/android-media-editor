package io.github.toyota32k.lib.media.editor.handler.split

import android.content.Context
import androidx.core.net.toUri
import io.github.toyota32k.lib.media.editor.handler.save.IProgressSinkProvider
import io.github.toyota32k.lib.media.editor.handler.save.ISaveFileTask
import io.github.toyota32k.lib.media.editor.handler.save.ISaveResult
import io.github.toyota32k.lib.media.editor.handler.save.SaveTaskStatus
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.ISplitHandler
import io.github.toyota32k.lib.media.editor.model.IVideoSourceInfo
import io.github.toyota32k.media.lib.converter.ConvertSplitter
import io.github.toyota32k.media.lib.converter.IMultiChopper
import io.github.toyota32k.media.lib.converter.IMultiPartitioner
import io.github.toyota32k.media.lib.converter.IMultiSplitResult
import io.github.toyota32k.media.lib.converter.IOutputFileSelector
import io.github.toyota32k.media.lib.converter.IProgress
import io.github.toyota32k.media.lib.converter.Splitter
import io.github.toyota32k.media.lib.converter.TrimSplitter
import io.github.toyota32k.media.lib.converter.toAndroidFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow


abstract class AbstractSplitHandler(showSplitButton:Boolean) : ISplitHandler {
    val logger = AmeGlobal.logger
    override val showSplitButton = MutableStateFlow(showSplitButton)
    companion object {
        const val MIN_RANGE = 100L // 100ms
    }
}

object NoopSplitHandler : ISplitHandler {
    override val showSplitButton: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun splitAtCurrentPosition(sourceInfo: IVideoSourceInfo):IMultiSplitResult? { return null }
    override suspend fun splitByChapters(sourceInfo: IVideoSourceInfo):IMultiSplitResult? { return null }
}

interface ISplitTask : ISaveFileTask, IProgressSinkProvider {
    val convertIfNeed:Boolean
    val fileSelector: IOutputFileSelector
}

class SplitResult(val result: IMultiSplitResult): ISaveResult {
    override val status: ISaveResult.Status
        get() = when {
            result.succeeded -> ISaveResult.Status.SUCCESS
            result.cancelled -> ISaveResult.Status.CANCELLED
            else -> ISaveResult.Status.ERROR
        }
    override val error: Throwable?
        get() = result.exception
    override val errorMessage: String?
        get() = result.errorMessage ?: error?.message
}

/**
 * 現在の再生位置で分割する
 */
class GenericSplitHandler(showSplitButton: Boolean, val applicationContext: Context, val startSplitTask:()->ISplitTask?):AbstractSplitHandler(true) {
    var task: ISplitTask? = null

    override suspend fun splitAtCurrentPosition(sourceInfo: IVideoSourceInfo): IMultiSplitResult {
        val task = startSplitTask() ?: return Splitter.MultiResult().cancel()
        this.task = task

        if (sourceInfo.positionMs<MIN_RANGE||sourceInfo.durationMs-MIN_RANGE < sourceInfo.durationMs) {
            logger.warn("requested range is too short.")
            return Splitter.MultiResult().error(IllegalArgumentException("requested range is too short."))
        }

        val inFile = sourceInfo.source.uri.toUri().toAndroidFile(applicationContext)

        val splitter: IMultiChopper = when {
            task.convertIfNeed && sourceInfo.needsReEncoding -> {
                ConvertSplitter
                    .builder
                    .setProgressHandler(::onProgress)
                    .trimming {
                        addRangesMs(sourceInfo.trimmingRanges)
                    }
                    .apply {
                        if (sourceInfo.cropRect!=null) {
                            crop(sourceInfo.cropRect!!)
                        }
                        if (sourceInfo.brightness!=null) {
                            brightness(sourceInfo.brightness!!)
                        }
                    }
                    .build()
            }
            sourceInfo.trimmingRanges.isNotEmpty() -> {
                TrimSplitter
                    .builder
                    .setProgressHandler(::onProgress)
                    .trimming {
                        addRangesMs(sourceInfo.trimmingRanges)
                    }
                    .build()
            }
            else -> {
                Splitter.builder.setProgressHandler(::onProgress).build()
            }
        }
        task.onStart(SaveTaskStatus.CONVERTING) { splitter.cancel() }
        try {
            return splitter.chop(inFile, listOf(sourceInfo.positionMs), task.fileSelector).apply {
                task.onEnd(SaveTaskStatus.CONVERTING, SplitResult(this))
            }
        } finally {
            task.onFinished()
            this.task = null
        }
    }

    override suspend fun splitByChapters(sourceInfo: IVideoSourceInfo): IMultiSplitResult {
        val task = startSplitTask() ?: return Splitter.MultiResult().cancel()
        this.task = task

        if (sourceInfo.positionMs<MIN_RANGE||sourceInfo.durationMs-MIN_RANGE < sourceInfo.durationMs) {
            logger.warn("requested range is too short.")
            return Splitter.MultiResult().error(IllegalArgumentException("requested range is too short."))
        }

        val inFile = sourceInfo.source.uri.toUri().toAndroidFile(applicationContext)

        val splitter: IMultiPartitioner = when {
            task.convertIfNeed && sourceInfo.needsReEncoding -> {
                ConvertSplitter
                    .builder
                    .setProgressHandler(::onProgress)
                    .trimming {
                        addRangesMs(sourceInfo.trimmingRanges)
                    }
                    .apply {
                        if (sourceInfo.cropRect!=null) {
                            crop(sourceInfo.cropRect!!)
                        }
                        if (sourceInfo.brightness!=null) {
                            brightness(sourceInfo.brightness!!)
                        }
                    }
                    .build()
            }
            sourceInfo.trimmingRanges.isNotEmpty() -> {
                TrimSplitter
                    .builder
                    .setProgressHandler(::onProgress)
                    .trimming {
                        addRangesMs(sourceInfo.trimmingRanges)
                    }
                    .build()
            }
            else -> {
                throw IllegalArgumentException("not supported")
               //  Splitter.builder.setProgressHandler(::onProgress).build()
            }
        }
        task.onStart(SaveTaskStatus.CONVERTING) { splitter.cancel() }
        try {
            return splitter.chop(inFile, task.fileSelector).apply {
                task.onEnd(SaveTaskStatus.CONVERTING, SplitResult(this))
            }
        } finally {
            task.onFinished()
            this.task = null
        }
    }

    fun onProgress(progress: IProgress) {
        task?.progressSink?.onProgress(SaveTaskStatus.CONVERTING, progress)
    }
}

