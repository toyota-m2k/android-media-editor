package io.github.toyota32k.lib.media.editor.handler.save

import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.lib.media.editor.dialog.SelectQualityDialog
import io.github.toyota32k.lib.media.editor.model.TrialConvertHelper
import io.github.toyota32k.lib.media.editor.model.IVideoSourceInfo
import io.github.toyota32k.media.lib.io.IInputMediaFile
import io.github.toyota32k.media.lib.strategy.IVideoStrategy
import io.github.toyota32k.media.lib.types.Rotation
import java.lang.IllegalStateException

/**
 * UI (SelectQualityDialog) を表示して VideoStrategy を選択可能とする IVideoStrategySelector
 */
class InteractiveVideoStrategySelector() : IVideoStrategyAndHdrSelector {
    private var mKeepHdr:Boolean? = null
    override val keepHdr: Boolean
        get() = mKeepHdr ?: throw IllegalStateException("call getVideoStrategy first")

    override suspend fun getVideoStrategy(inputFile: IInputMediaFile, sourceInfo: IVideoSourceInfo): IVideoStrategy? {
        return UtImmortalTask.awaitTaskResult("SelectVideoStrategy") {
            val helper = TrialConvertHelper(inputFile,  true, Rotation(sourceInfo.rotation, true), sourceInfo.cropRect, sourceInfo.trimmingRanges, sourceInfo.durationMs)
            SelectQualityDialog.show(true, helper, sourceInfo.positionMs)?.run {
                mKeepHdr = keepHdr
                quality.strategy
            }
        }
    }

}