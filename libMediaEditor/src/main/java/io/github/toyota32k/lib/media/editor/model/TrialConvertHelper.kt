package io.github.toyota32k.lib.media.editor.model

import android.content.Context
import android.graphics.Rect
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.launchSubTask
import io.github.toyota32k.dialog.task.showConfirmMessageBox
import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.lib.io.IInputMediaFile
import io.github.toyota32k.media.lib.processor.Processor
import io.github.toyota32k.media.lib.processor.ProcessorOptions
import io.github.toyota32k.media.lib.processor.contract.IConvertResult
import io.github.toyota32k.media.lib.processor.contract.format
import io.github.toyota32k.media.lib.report.Report
import io.github.toyota32k.media.lib.strategy.IVideoStrategy
import io.github.toyota32k.media.lib.strategy.PresetAudioStrategies
import io.github.toyota32k.media.lib.strategy.PresetVideoStrategies
import io.github.toyota32k.media.lib.types.RangeMs
import io.github.toyota32k.media.lib.types.Rotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.seconds

class TrialConvertHelper(
    val inputFile: IInputMediaFile,
    var keepHdr: Boolean,
    val rotation: Rotation,
    val cropRect: Rect?,
    val trimmingRanges: List<RangeMs>,
    val durationMs:Long,
    var videoStrategy: IVideoStrategy = PresetVideoStrategies.InvalidStrategy,
) {
    val logger = UtLog("CH", AmeGlobal.logger)
    var trimFileName: String = "trim"
//    var optFileName: String = "opt"

    lateinit var result: IConvertResult
        private set
    @Suppress("unused")
    val report: Report? get() = result.report

    /**
     * トリミング実行後の再生時間
     */
    val trimmedDuration:Long
        get() = calcTrimmedDuration(durationMs, trimmingRanges)

    private suspend fun convert(applicationContext: Context, limitDuration:Long, ranges: List<RangeMs>?): File {
        return UtImmortalTask.awaitTaskResult("ConvertHelper") {
            val vm = createViewModel<ProgressDialog.ProgressViewModel>()
            vm.message.value = "Trimming Now..."
            val trimFile = File(applicationContext.cacheDir ?: throw IllegalStateException("no cacheDir"), trimFileName)

            val processor = Processor()
            val processorOptions = ProcessorOptions.Builder()
                .input(inputFile)
                .output(trimFile)
                .audioStrategy(PresetAudioStrategies.AACDefault)
                .videoStrategy(videoStrategy)
                .crop(cropRect)
                .keepHDR(keepHdr)
                .rotate(rotation)
                .trimming {
                    addRangesMs(ranges ?: trimmingRanges)
                }
                .limitDuration(limitDuration)
                .onProgress {
                    vm.progress.value = it.percentage
                    vm.progressText.value = it.format()
                }
                .build()

            vm.cancelCommand.bindForever { processor.cancel() }
            launchSubTask { showDialog("ConvertHelper.ProgressDialog") { ProgressDialog() } }

            withContext(Dispatchers.IO) {
                try {
                    val r = processor.execute(processorOptions).apply { result = this }
                    if (!r.succeeded) {
                        if (r.cancelled) {
                            throw CancellationException("conversion cancelled")
                        } else {
                            throw r.exception ?: IllegalStateException("unknown error")
                        }
                    }
                    trimFile
                } catch (e: Throwable) {
                    trimFile.safeDelete()
                    throw e
                } finally {
                    withContext(Dispatchers.Main) { vm.closeCommand.invoke(true) }
                }
            }
        }
    }
    private fun File.safeDelete() {
        try {
            if (exists()) {
                delete()
            }
        } catch (_:Throwable) {}
    }

    private suspend fun safeConvert(applicationContext: Context, limitDuration: Long, ranges: List<RangeMs>?=null): File? {
        return try {
            convert(applicationContext, limitDuration, ranges)
        } catch (_: CancellationException) {
            logger.info("conversion cancelled")
            null
        } catch (e: Throwable) {
            logger.stackTrace(e)
            UtImmortalTask("ConvertHelper.Error") {
                showConfirmMessageBox(
                    "Conversion Error",
                    e.localizedMessage ?: e.message ?: "Something wrong."
                )
            }
            null
        }
    }

    /**
     * 指定位置 (convertFrom) より後ろの総再生時間を計算する
     */
    private fun calcRemainingDurationAfter(convertFrom:Long, duration:Long):Long {
        return trimmingRanges.fold(duration) { acc, range ->
            val end = if (range.endMs==0L) duration else range.endMs
            when {
                end < convertFrom -> acc
                range.startMs <= convertFrom -> acc + (end - convertFrom)
                else -> acc + end - range.startMs
            }
        }
    }

    /**
     * 開始位置(convertFrom)に合わせてthis.trimmingRangeを調整し、新しいTrimmingRanges配列を作成する。
     */
    private fun adjustTrimmingRangeWithPosition(convertFrom:Long, limitDuration:Long):List<RangeMs> {
        // pos 以降の残り時間を計算
        val remaining = calcRemainingDurationAfter(convertFrom, durationMs)
        return if (remaining>=limitDuration) {
            // 残り時間が十分ある場合は、posを起点とする TrimmingRanges配列を作成
            trimmingRanges.fold(mutableListOf()) { acc, range ->
                val end = if (range.endMs==0L) durationMs else range.endMs
                when {
                    end < convertFrom -> {}
                    range.startMs <= convertFrom -> acc.add(RangeMs(convertFrom, end))
                    else -> acc.add(range)
                }
                acc
            }
        } else {
            // 残り時間が不足する場合は、
            var total = 0L
            mutableListOf<RangeMs>().also { list ->
                for (range in trimmingRanges.reversed()) {
                    val end = if (range.endMs == 0L) durationMs else range.endMs
                    total += (end - range.startMs)
                    if (total <= limitDuration) {
                        list.add(0, range)
                    } else {
                        val remain = total - limitDuration
                        if (remain > 1000) {
                            list.add(0, RangeMs(end - remain, end))
                        }
                        break
                    }
                }
            }
        }
    }

    suspend fun tryConvert(applicationContext: Context, convertFrom:Long, limitDuration:Long=10.seconds.inWholeMilliseconds): File? {
//        assert(videoStrategy != null) { "tryConvert: videoStrategy is null" }
        val duration = calcTrimmedDuration(durationMs, trimmingRanges)
        val ranges = if (duration<=limitDuration) {
            // トリミング後の再生時間が既定時間(limitDuration)に満たない場合は、convertFromは無視して先頭から
            null
        } else {
            // convertFrom と limitDuration から、試行用の trimmingRanges 配列を作成する
            adjustTrimmingRangeWithPosition(convertFrom, limitDuration)
        }
        return safeConvert(applicationContext, limitDuration, ranges)
    }

    companion object {
        // Trimming後のdurationを計算
        fun calcTrimmedDuration(duration:Long, trimmingRanges: List<RangeMs>):Long {
            return if (trimmingRanges.isEmpty()) duration else trimmingRanges.fold(0L) { acc, range ->
                val end = if (range.endMs==0L) duration else range.endMs
                acc + end - range.startMs
            }
        }
    }
}