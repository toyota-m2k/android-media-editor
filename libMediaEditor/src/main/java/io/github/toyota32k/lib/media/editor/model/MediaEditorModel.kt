package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.media.lib.converter.Converter
import io.github.toyota32k.utils.IUtPropOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.Closeable

open class MediaEditorModel(
    val playerControllerModel: PlayerControllerModel,
    val chapterEditorHandler: IChapterEditorHandler,
    val cropHandler:ICropHandler,
    val splitHandler: ISplitHandler,
    val saveFileHandler: ISaveFileHandler,
    ) : IUtPropOwner, Closeable
{
    val logger = AmeGlobal.logger
    val playerModel: IPlayerModel = playerControllerModel.playerModel
    val savingNow: StateFlow<Boolean> = MutableStateFlow(false)
    val isDirty: Flow<Boolean> by lazy {
        combine(chapterEditorHandler.chapterListModified, cropHandler.isCropped, cropHandler.isResolutionChanged) { chapter, crop, resolution ->
            chapter || crop || resolution
        }
    }

    enum class EditMode {
        NORMAL,
        CROP,
        RESOLUTION,
    }
    val editMode: Flow<EditMode> = combine(cropHandler.croppingNow, cropHandler.resolutionChangingNow) { crop, resolution ->
        when {
            resolution -> EditMode.RESOLUTION
            crop -> EditMode.CROP
            else -> EditMode.NORMAL
        }
    }.stateIn(playerModel.scope, SharingStarted.Lazily, EditMode.NORMAL)

    open fun onMagnifyTimeline() {}


    class ImageSourceInfoImpl(
        override val source: IMediaSource,
        override val editedBitmap: Bitmap
    ) : IImageSourceInfo

    class VideoSourceInfoImpl(
        override val source: IMediaSource,
        override val trimmingRanges: Array<Converter.Factory.RangeMs>,
        override val rotation: Int,
        override val cropRect: Rect?,
        override val brightness: Float?,
        override val positionMs: Long,
        override val durationMs: Long) : IVideoSourceInfo {
        companion object {
            fun fromModel(model: MediaEditorModel): VideoSourceInfoImpl? {
                val source = model.playerModel.currentSource.value ?: return null
                val size = model.playerModel.videoSize.value ?: return null
                val ranges = model.chapterEditorHandler.getEnabledRangeList().map { Converter.Factory.RangeMs(it.start, it.end) }.toTypedArray()
                val rotation = model.playerModel.rotation.value
                val cropRect = if (model.cropHandler.maskViewModel.isCropped.value) model.cropHandler.maskViewModel.cropRect(size.width, size.height).asRect else null
                val positionMs = model.playerModel.currentPosition
                val durationMs = model.playerModel.naturalDuration.value
                return VideoSourceInfoImpl(source, ranges, rotation, cropRect, null/*for future*/, positionMs, durationMs)
            }
        }
    }

    open suspend fun saveFile():Boolean {
        val item = playerModel.currentSource.value ?: return false
        savingNow.mutable.value = true
        return try {
            if (item.isPhoto) {
                val bitmap = cropHandler.cropImageModel.crop() ?: return false
                val sourceInfo = ImageSourceInfoImpl(item, bitmap)
                saveFileHandler.saveImage(sourceInfo)
            } else if (item.type.lowercase() == "mp4") {
//                val size = playerModel.videoSize.value ?: return false
//                val ranges = chapterEditorHandler.getEnabledRangeList().map { Converter.Factory.RangeMs(it.start, it.end) }.toTypedArray()
//                val cropRect = if (cropHandler.maskViewModel.isCropped.value) cropHandler.maskViewModel.cropRect(size.width, size.height).asRect else null
//                saveFileHandler.saveVideo(ranges, playerModel.rotation.value, cropRect, 1f)
                val sourceInfo = VideoSourceInfoImpl.fromModel(this) ?: return false
                saveFileHandler.saveVideo(sourceInfo)
            } else {
                false
            }
        } catch (e: Throwable) {
            logger.error(e)
            return false
        } finally {
            savingNow.mutable.value = false
        }
    }
    open suspend fun splitVideo():Boolean {
        val item = playerModel.currentSource.value ?: return false
        val pos = playerModel.currentPosition
        if (pos < 1000 || playerModel.naturalDuration.value-1000 < pos) return false // 1sec未満の分割は禁止
        savingNow.mutable.value = true
        return try {
            splitHandler.splitVideoAt(item, pos)
        } catch (e:Throwable) {
            logger.error(e)
            false
        } finally {
            savingNow.mutable.value = false
        }
    }

    override fun close() {
        playerControllerModel.close()
        cropHandler.dispose()
    }

    class Builder(val playerControllerModel: PlayerControllerModel) {
        private var saveFileHandler: ISaveFileHandler? = null
        private var mChapterEditorHandler: IChapterEditorHandler? = null
        private var mCropHandler: ICropHandler? = null
        private var mSplitHandler: ISplitHandler? = null

        fun setSaveFileHandler(handler: ISaveFileHandler) :Builder = apply {
            saveFileHandler = handler
        }
        fun setSaveFileHandler(fn:(PlayerControllerModel)-> ISaveFileHandler) :Builder = apply {
            saveFileHandler = fn(playerControllerModel)
        }

        fun supportChapterEditor(handler: IChapterEditorHandler?=null) :Builder = apply {
            mChapterEditorHandler = handler ?: ChapterEditorHandler(playerControllerModel.playerModel, true)
        }
        fun supportCrop(handler: ICropHandler?=null) :Builder = apply {
            mCropHandler = handler ?: CropHandler(playerControllerModel.playerModel, true, true)
        }
        fun supportSplit(handler: ISplitHandler): Builder = apply {
            mSplitHandler = handler
        }
        fun build() :MediaEditorModel {
            val saveFileHandler = this.saveFileHandler
            if (saveFileHandler == null) throw IllegalStateException("saveFileHandler is not set")
            return MediaEditorModel(
                playerControllerModel,
                mChapterEditorHandler ?: ChapterEditorHandler(playerControllerModel.playerModel, false),
                mCropHandler ?: CropHandler(playerControllerModel.playerModel, false, false),
                mSplitHandler ?: NoopSplitHandler,
                saveFileHandler,
                )
        }
    }
}