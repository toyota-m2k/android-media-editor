package io.github.toyota32k.lib.media.editor.model

import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.media.lib.converter.Converter
import io.github.toyota32k.media.lib.converter.Rotation
import io.github.toyota32k.utils.IUtPropOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.collections.toTypedArray

open class MediaEditorModel(
    val playerControllerModel: PlayerControllerModel,
    val chapterEditorHandler: IChapterEditorHandler,
    val cropHandler:ICropHandler,
    val splitHandler: ISplitHandler,
    val saveFileHandler: ISaveFileHandler,
    val supportMagnifyingTimeline: Boolean) : IUtPropOwner
{
    val logger = AmeGlobal.logger
    val playerModel: IPlayerModel = playerControllerModel.playerModel
    val savingNow: StateFlow<Boolean> = MutableStateFlow(false)
    val isDirty: Flow<Boolean> = combine(chapterEditorHandler.chapterListModified, cropHandler.isCropped, cropHandler.isResolutionChanged) { chapter, crop, resolution ->
        chapter || crop || resolution
    }

    enum class EditMode {
        NONE,
        CROPPING,
        RESOLUTION_CHANGING,
    }
    val editMode: Flow<EditMode> = combine(cropHandler.croppingNow, cropHandler.resolutionChangingNow)  {c,r->
        when {
            r -> EditMode.RESOLUTION_CHANGING
            c -> EditMode.CROPPING
            else -> EditMode.NONE
        }
    }

    open fun onMagnifyTimeline() {}
    open fun saveFile() {
        val item = playerModel.currentSource.value ?: return
        savingNow.mutable.value = true
        CoroutineScope(playerModel.scope.coroutineContext + SupervisorJob()).launch {
            try {
                if (item.isPhoto) {
                    val bitmap = cropHandler.cropImageModel.crop() ?: return@launch
                    saveFileHandler.saveImage(bitmap)
                } else if (item.type.lowercase() == "mp4") {
                    val size = playerModel.videoSize.value ?: return@launch
                    val ranges = chapterEditorHandler.getEnabledRangeList().map { Converter.Factory.RangeMs(it.start, it.end) }.toTypedArray()
                    val cropRect = if (cropHandler.maskViewModel.isCropped.value) cropHandler.maskViewModel.cropRect(size.width, size.height).asRect else null
                    saveFileHandler.saveVideo(ranges, playerModel.rotation.value, cropRect, 1f)
                }
            } catch (e: Throwable) {
                logger.error(e)
            } finally {
                savingNow.mutable.value = false
            }
        }
    }
    open fun splitVideo() {
        val item = playerModel.currentSource.value ?: return
        val pos = playerModel.currentPosition
        if (pos < 1000 || playerModel.naturalDuration.value-1000 < pos) return  // 1sec未満の分割は禁止
        savingNow.mutable.value = true
        CoroutineScope(playerModel.scope.coroutineContext + SupervisorJob()).launch {
            try {
                splitHandler.splitVideoAt(item, pos)
            } catch (e:Throwable) {
                logger.error(e)
            } finally {
                savingNow.mutable.value = false
            }
        }
    }

    class Builder(private val mPlayerControllerModel: PlayerControllerModel) {
        private var mChapterEditorHandler: IChapterEditorHandler? = null
        private var mCropHandler: ICropHandler? = null
        private var mSplitHandler: ISplitHandler? = null
        private var mSaveFileHandler: ISaveFileHandler? = null
        private var mSupportMagnifyingTimeline: Boolean = false

        fun supportChapterEditor(sw:Boolean) :Builder = apply {
            mChapterEditorHandler = ChapterEditorHandler(mPlayerControllerModel.playerModel, sw)
        }
        fun supportCrop(sw:Boolean) :Builder = apply {
            mCropHandler = CropHandler(mPlayerControllerModel.playerModel, sw)
        }
        fun setChapterEditorHandler(handler: IChapterEditorHandler): Builder = apply {
            mChapterEditorHandler = handler
        }
        fun setCropHandler(handler: ICropHandler): Builder = apply {
            mCropHandler = handler
        }
        fun setSplitHandler(handler: ISplitHandler): Builder = apply {
            mSplitHandler = handler
        }
        fun setSaveFileHandler(handler: ISaveFileHandler): Builder = apply {
            mSaveFileHandler = handler
        }
        fun supportMagnifyingTimeline(sw:Boolean) :Builder = apply {
            mSupportMagnifyingTimeline = sw
        }
        fun build() :MediaEditorModel {
            val saveFileHandler = mSaveFileHandler ?: throw IllegalStateException("saveFileHandler is not set")

            return MediaEditorModel(
                mPlayerControllerModel,
                mChapterEditorHandler ?: ChapterEditorHandler(mPlayerControllerModel.playerModel, false),
                mCropHandler ?: CropHandler(mPlayerControllerModel.playerModel, false),
                mSplitHandler ?: NoopSplitHandler,
                saveFileHandler,
                mSupportMagnifyingTimeline)
        }
    }
}