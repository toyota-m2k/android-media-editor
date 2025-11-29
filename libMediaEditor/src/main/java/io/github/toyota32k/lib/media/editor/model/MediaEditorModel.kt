package io.github.toyota32k.lib.media.editor.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.common.primitives.Longs.min
import io.github.toyota32k.lib.media.editor.dialog.SliderPartition
import io.github.toyota32k.lib.media.editor.dialog.SliderPartitionDialog
import io.github.toyota32k.lib.media.editor.handler.split.ExportToDirectoryFileSelector
import io.github.toyota32k.lib.media.editor.handler.split.GenericSplitHandler
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.media.lib.converter.RangeMs
import io.github.toyota32k.utils.IUtPropOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.Closeable
import kotlin.math.max

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
    val isDirty: Boolean get() = chapterEditorHandler.isDirty || cropHandler.isDirty

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
        override val trimmingRanges: List<RangeMs>,
        override val rotation: Int,
        override val cropRect: Rect?,
        override val brightness: Float?,
        override val positionMs: Long,
        override val durationMs: Long) : IVideoSourceInfo {
        companion object {
            fun fromModel(model: MediaEditorModel): VideoSourceInfoImpl? {
                val source = model.playerModel.currentSource.value ?: return null
                val size = model.playerModel.videoSize.value ?: return null
                val ranges = model.chapterEditorHandler.getEnabledRangeList().map { RangeMs(it.start, it.end) }
                val rotation = model.playerModel.rotation.value
                val cropRect = if (model.cropHandler.maskViewModel.isCropped.value) model.cropHandler.maskViewModel.cropRect(size.width, size.height).asRect else null
                val positionMs = model.playerModel.currentPosition
                val durationMs = model.playerModel.naturalDuration.value
                return VideoSourceInfoImpl(source, ranges, rotation, cropRect, null/*for future*/, positionMs, durationMs)
            }
            fun fromModel(model: MediaEditorModel, mode:SaveMode): VideoSourceInfoImpl? {
                val source = model.playerModel.currentSource.value ?: return null
                val size = model.playerModel.videoSize.value ?: return null
                val durationMs = model.playerModel.naturalDuration.value
                val positionMs = model.playerModel.currentPosition
                val enabledRanges = model.chapterEditorHandler.getEnabledRangeList()
                val ranges = when(mode) {
                    SaveMode.ALL->enabledRanges.map { RangeMs(it.start, it.end) }
                    SaveMode.LEFT->enabledRanges.mapNotNull { if (it.start <= positionMs) RangeMs(it.start, min(it.actualEnd(durationMs), positionMs)) else null }
                    SaveMode.RIGHT->enabledRanges.mapNotNull { if (positionMs < it.end) RangeMs(max(it.start, positionMs), it.actualEnd(durationMs)) else null }
                    SaveMode.CHAPTER->enabledRanges.mapNotNull { if (it.contains(positionMs)) RangeMs(positionMs, it.actualEnd(durationMs)) else null }
                }
                if (ranges.isEmpty() && mode!= SaveMode.ALL) return null
                val rotation = model.playerModel.rotation.value
                val cropRect = if (model.cropHandler.maskViewModel.isCropped.value) model.cropHandler.maskViewModel.cropRect(size.width, size.height).asRect else null
                return VideoSourceInfoImpl(source, ranges, rotation, cropRect, null/*for future*/, positionMs, durationMs)
            }
        }
    }

    enum class SaveMode {
        ALL,
        LEFT,
        RIGHT,
        CHAPTER,
    }

    open suspend fun saveVideo(mode:SaveMode, outputFileProvider:IOutputFileProvider):Boolean {
        if (mode == SaveMode.ALL) {
            return saveFile(outputFileProvider)
        }
        val item = playerModel.currentSource.value ?: return false
        savingNow.mutable.value = true
        return try {
            if (item.type.lowercase() == "mp4") {
                val sourceInfo = VideoSourceInfoImpl.fromModel(this, mode) ?: return false
                saveFileHandler.saveVideo(sourceInfo, outputFileProvider)
            } else false
        } catch (e: Throwable) {
            logger.error(e)
            false
        } finally {
            savingNow.mutable.value = false
        }
    }

    open suspend fun saveFile(outputFileProvider:IOutputFileProvider):Boolean {
        val item = playerModel.currentSource.value ?: return false
        savingNow.mutable.value = true
        return try {
            if (item.isPhoto) {
                val bitmap = cropHandler.cropImageModel.crop() ?: return false
                val sourceInfo = ImageSourceInfoImpl(item, bitmap)
                saveFileHandler.saveImage(sourceInfo, outputFileProvider)
            } else if (item.type.lowercase() == "mp4") {
                val sourceInfo = VideoSourceInfoImpl.fromModel(this) ?: return false
                saveFileHandler.saveVideo(sourceInfo, outputFileProvider)
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
    enum class SplitMode {
        AT_POSITION,
        BY_CHAPTERS,
    }
    suspend fun splitVideo(mode:SplitMode):Boolean {
//        val item = playerModel.currentSource.value ?: return false
        savingNow.mutable.value = true
        return try {
            val sourceInfo = VideoSourceInfoImpl.fromModel(this) ?: return false
            val result = when (mode) {
                SplitMode.AT_POSITION -> splitHandler.splitAtCurrentPosition(sourceInfo, true, ExportToDirectoryFileSelector())
                SplitMode.BY_CHAPTERS -> splitHandler.splitByChapters(sourceInfo, true, ExportToDirectoryFileSelector())
                // else -> throw IllegalArgumentException("mode = $mode")
            }
            result?.succeeded == true
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

    fun builder(androidContext: Context, viewModelScope: CoroutineScope, playerControllerModelInitializer:PlayerControllerModel.Builder.()-> PlayerControllerModel.Builder):Builder {
        return Builder(PlayerControllerModel.Builder(androidContext, viewModelScope).playerControllerModelInitializer())
    }

    class Builder (val playerControllerModelBuilder: PlayerControllerModel.Builder) {
        constructor (androidContext: Context, viewModelScope: CoroutineScope, playerControllerModelInitializer:PlayerControllerModel.Builder.()-> PlayerControllerModel.Builder):this(PlayerControllerModel.Builder(androidContext, viewModelScope).playerControllerModelInitializer())

        private var mSaveFileHandler: ISaveFileHandler? = null

        private var mChapterEditorHandler: IChapterEditorHandler? = null
        private var mChapterEditorRequired = false

        private var mCropHandler: ICropHandler? = null
        private var mCropRequired = false

        private var mSplitHandler: ISplitHandler? = null
        private var mSplitRequired = false

//        fun setSaveFileHandler(fn:(PlayerControllerModel)-> ISaveFileHandler) :Builder = apply {
//            saveFileHandler = fn(playerControllerModel)
//        }

        fun supportChapterEditor(handler: IChapterEditorHandler?=null) :Builder = apply {
            mChapterEditorHandler = handler
            mChapterEditorRequired = true
                //?: ChapterEditorHandler(playerControllerModel.playerModel, true)
        }
        fun supportCrop(handler: ICropHandler?=null) :Builder = apply {
            mCropHandler = handler
            mCropRequired = true
                // ?: CropHandler(playerControllerModel.playerModel, true, true)
        }
        fun supportSplit(handler: ISplitHandler?=null): Builder = apply {
            mSplitHandler = handler
            mSplitRequired = true
        }
        fun setSaveFileHandler(handler: ISaveFileHandler) :Builder = apply {
            mSaveFileHandler = handler
        }

        fun enableBuiltInMagnifySlider() = apply {
            playerControllerModelBuilder.supportMagnifySlider { orgModel, duration->
                val sp = SliderPartitionDialog.show(SliderPartition.fromModel(orgModel, duration))
                if (sp==null) orgModel else sp.toModel()
            }
        }

        fun build() :MediaEditorModel {
            val saveFileHandler = mSaveFileHandler ?: throw IllegalStateException("saveFileHandler is not set")
            val playerControllerModel = playerControllerModelBuilder.build()
            val chapterEditorHandler =  mChapterEditorHandler ?:ChapterEditorHandler(playerControllerModel.playerModel, mChapterEditorRequired)
            val cropHandler = mCropHandler ?: CropHandler(playerControllerModel.playerModel, mCropRequired, mCropRequired)
            val splitHandler = mSplitHandler ?: GenericSplitHandler(playerControllerModel.context, mSplitRequired)

            return MediaEditorModel(
                playerControllerModel,
                chapterEditorHandler,
                cropHandler,
                splitHandler,
                saveFileHandler,
                )
        }
    }
}