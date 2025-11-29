package io.github.toyota32k.lib.media.editor.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.common.primitives.Longs.min
import io.github.toyota32k.lib.media.editor.dialog.SliderPartition
import io.github.toyota32k.lib.media.editor.dialog.SliderPartitionDialog
import io.github.toyota32k.lib.media.editor.handler.ExportFileProvider
import io.github.toyota32k.lib.media.editor.handler.save.DefaultAudioStrategySelector
import io.github.toyota32k.lib.media.editor.handler.save.GenericSaveFileHandler
import io.github.toyota32k.lib.media.editor.handler.save.InteractiveVideoStrategySelector
import io.github.toyota32k.lib.media.editor.handler.split.ExportToDirectoryFileSelector
import io.github.toyota32k.lib.media.editor.handler.split.GenericSplitHandler
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.media.lib.converter.IOutputFileSelector
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
    val outputFileSelectorResolver:((IMediaSource)->IOutputFileSelector?)?,
    val outputFileProviderResolver:((IMediaSource)-> IOutputFileProvider?)?,
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
                if (model.playerModel.isCurrentSourcePhoto.value) return null
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
                if (model.playerModel.isCurrentSourcePhoto.value) return null
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

    open suspend fun saveVideo(mode:SaveMode, outputFileProvider:IOutputFileProvider?):Boolean {
        if (mode == SaveMode.ALL) {
            return saveFile(outputFileProvider)
        }
        val item = playerModel.currentSource.value ?: return false
        val provider = resolveFileProvider(outputFileProvider, item)
        savingNow.mutable.value = true
        return try {
            if (item.type.lowercase() == "mp4") {
                val sourceInfo = VideoSourceInfoImpl.fromModel(this, mode) ?: return false
                saveFileHandler.saveVideo(sourceInfo, provider)
            } else false
        } catch (e: Throwable) {
            logger.error(e)
            false
        } finally {
            savingNow.mutable.value = false
        }
    }

    fun resolveFileProvider(outputFileProvider:IOutputFileProvider?, item:IMediaSource) : IOutputFileProvider {
        return outputFileProvider ?: outputFileProviderResolver?.invoke(item) ?: ExportFileProvider("-EDITED")
    }
    fun resolveFileSelector(outputFileSelector:IOutputFileSelector?, item:IMediaSource) : IOutputFileSelector {
        return outputFileSelector ?: outputFileSelectorResolver?.invoke(item) ?: ExportToDirectoryFileSelector()
    }

    open suspend fun saveFile(outputFileProvider:IOutputFileProvider?):Boolean {
        val item = playerModel.currentSource.value ?: return false
        savingNow.mutable.value = true
        val provider = resolveFileProvider(outputFileProvider, item)
        return try {
            if (item.isPhoto) {
                val bitmap = cropHandler.cropImageModel.crop() ?: return false
                val sourceInfo = ImageSourceInfoImpl(item, bitmap)
                saveFileHandler.saveImage(sourceInfo, provider)
            } else if (item.type.lowercase() == "mp4") {
                val sourceInfo = VideoSourceInfoImpl.fromModel(this) ?: return false
                saveFileHandler.saveVideo(sourceInfo, provider)
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
        val item = playerModel.currentSource.value ?: return false
        if (item.isPhoto) return false
        savingNow.mutable.value = true
        return try {
            val sourceInfo = VideoSourceInfoImpl.fromModel(this) ?: return false
            val result = when (mode) {
                SplitMode.AT_POSITION -> splitHandler.splitAtCurrentPosition(sourceInfo, true, resolveFileSelector(null, item))
                SplitMode.BY_CHAPTERS -> splitHandler.splitByChapters(sourceInfo, true, resolveFileSelector(null, item))
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

    class Builder (val playerControllerModelBuilder: PlayerControllerModel.Builder) {
        constructor (androidContext: Context, viewModelScope: CoroutineScope, playerControllerModelInitializer:PlayerControllerModel.Builder.()-> PlayerControllerModel.Builder):this(PlayerControllerModel.Builder(androidContext, viewModelScope).playerControllerModelInitializer())

        private var mSaveFileHandler: ISaveFileHandler? = null

        private var mChapterEditorHandler: IChapterEditorHandler? = null
        private var mChapterEditorRequired = false

        private var mCropHandler: ICropHandler? = null
        private var mCropRequired = false

        private var mSplitHandler: ISplitHandler? = null
        private var mSplitRequired = false

        private var mOutputFileProviderResolver: ((IMediaSource) -> IOutputFileProvider?)? = null
        private var mOutputFileSelectorResolver: ((IMediaSource) -> IOutputFileSelector?)? = null

        /**
         * ファイル保存ハンドラのデフォルトの設定、
         * - InteractiveVideoStrategySelector を使用
         * - ツールバーの保存ボタンを表示
         * を変更したい場合に、カスタマイズしたハンドラを設定する。
         * @param handler
         */
        fun setSaveFileHandler(handler: ISaveFileHandler) :Builder = apply {
            mSaveFileHandler = handler
        }

        /**
         * チャプター編集サポートを追加する
         * @param handler nullならデフォルトのハンドラを使用。
         */
        fun supportChapterEditor(handler: IChapterEditorHandler?=null) :Builder = apply {
            mChapterEditorHandler = handler
            mChapterEditorRequired = true
                //?: ChapterEditorHandler(playerControllerModel.playerModel, true)
        }

        /**
         * 画像・動画映像の切り抜き編集サポートを追加する。
         * @param handler nullならデフォルトのハンドラを使用。
         */
        fun supportCrop(handler: ICropHandler?=null) :Builder = apply {
            mCropHandler = handler
            mCropRequired = true
                // ?: CropHandler(playerControllerModel.playerModel, true, true)
        }

        /**
         * 動画の時間分割編集サポートを追加する。
         * @param handler nullならデフォルトのハンドラを使用。
         */
        fun supportSplit(handler: ISplitHandler?=null): Builder = apply {
            mSplitHandler = handler
            mSplitRequired = true
        }

        /**
         * ビルトインのスライダー拡大機能を有効にする。
         */
        fun enableBuiltInMagnifySlider() = apply {
            playerControllerModelBuilder.supportMagnifySlider { orgModel, duration->
                val sp = SliderPartitionDialog.show(SliderPartition.fromModel(orgModel, duration))
                if (sp==null) orgModel else sp.toModel()
            }
        }

        /**
         * 動画ファイルの時間分割で使用する IOutputFileSelector を設定する。
         */
        fun setOutputFileSelector(selector: IOutputFileSelector) = apply {
            mOutputFileSelectorResolver = { selector }
        }
        /**
         * 動画ファイルの時間分割で使用する IOutputFileSelector を動的に取得するためのリゾルバを設定する。
         */
        fun setOutputFileSelector(resolver: (IMediaSource)->IOutputFileSelector) = apply {
            mOutputFileSelectorResolver = resolver
        }

        /**
         * ファイル保存に使用する IOutputFileProvider を設定する。
         */
        fun setOutputFileProvider(provider: IOutputFileProvider) = apply {
            mOutputFileProviderResolver = { provider }
        }

        /**
         * ファイル保存に使用する IOutputFileProvider を動的に取得するためのリゾルバを設定する。
         */
        fun setOutputFileProvider(resolver: (IMediaSource)->IOutputFileProvider) = apply {
            mOutputFileProviderResolver = resolver
        }

        /**
         * MediaEditorModelを構築
         */
        fun build() :MediaEditorModel {
            val playerControllerModel = playerControllerModelBuilder.build()
            // SaveFileHandler
            // デフォルト
            // - 保存ボタン表示
            // - InteractiveVideoStrategySelector を使用
            val saveFileHandler = mSaveFileHandler ?: GenericSaveFileHandler.create(playerControllerModel.context, true, InteractiveVideoStrategySelector(), DefaultAudioStrategySelector)
            val chapterEditorHandler =  mChapterEditorHandler ?:ChapterEditorHandler(playerControllerModel.playerModel, mChapterEditorRequired)
            val cropHandler = mCropHandler ?: CropHandler(playerControllerModel.playerModel, mCropRequired, mCropRequired)
            val splitHandler = mSplitHandler ?: GenericSplitHandler(playerControllerModel.context, mSplitRequired)

            return MediaEditorModel(
                playerControllerModel,
                chapterEditorHandler,
                cropHandler,
                splitHandler,
                saveFileHandler,
                mOutputFileSelectorResolver,
                mOutputFileProviderResolver
                )
        }
    }
}