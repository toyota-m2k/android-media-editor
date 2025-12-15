package io.github.toyota32k.lib.media.editor.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.common.primitives.Longs.min
import io.github.toyota32k.lib.media.editor.dialog.SliderPartition
import io.github.toyota32k.lib.media.editor.dialog.SliderPartitionDialog
import io.github.toyota32k.lib.media.editor.handler.ExportFileProvider
import io.github.toyota32k.lib.media.editor.handler.save.GenericSaveFileHandler
import io.github.toyota32k.lib.media.editor.handler.split.ExportToDirectoryFileSelector
import io.github.toyota32k.lib.media.editor.handler.split.GenericSplitHandler
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.media.lib.converter.IOutputFileSelector
import io.github.toyota32k.media.lib.utils.RangeMs
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

/**
 * EditorPlayerViewを使った、動画・画像編集のためのビューモデル総本山
 * @param playerControllerModel PlayerControllerModel (android-player)
 * @param chapterEditorHandler チャプター編集ハンドラ
 * @param cropHandler 画像・動画の切り抜き編集ハンドラ
 * @param splitHandler 動画の時間分割編集ハンドラ
 * @param saveFileHandler ファイル保存ハンドラ
 * @param outputFileSelectorResolver    // Split用 ファイル選択 i/f
 * @param outputFileProviderResolver    // Save用 ファイル選択 i/f
 */
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

    /**
     * 編集モード
     * - NORMAL     ... Chapter編集
     * - CROP       ... 映像（動画・画像）切り取り
     * - RESOLUTION ... 画像解像度変更
     */
    val editMode: Flow<EditMode> = combine(cropHandler.croppingNow, cropHandler.resolutionChangingNow) { crop, resolution ->
        when {
            resolution -> EditMode.RESOLUTION
            crop -> EditMode.CROP
            else -> EditMode.NORMAL
        }
    }.stateIn(playerModel.scope, SharingStarted.Lazily, EditMode.NORMAL)

    /**
     * 画像ソース情報
     */
    class ImageSourceInfoImpl(
        override val source: IMediaSource,
        override val editedBitmap: Bitmap
    ) : IImageSourceInfo

    /**
     * 動画ソース情報
     */
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
                    SaveMode.CURRENT_RANGES->enabledRanges.mapNotNull { if (it.contains(positionMs)) RangeMs(positionMs, it.actualEnd(durationMs)) else null }
                    SaveMode.CHAPTER-> {
                        // 現在の再生位置を含む単一チャプター（有効・無効は考慮しない）
                        val chapterList = model.chapterEditorHandler.getChapterList()
                        val neighbor = chapterList.getNeighborChapters(positionMs)
                        val start = if (neighbor.hit>=0) chapterList.chapters[neighbor.hit].position
                                    else if (neighbor.prev>=0) chapterList.chapters[neighbor.prev].position
                                    else 0
                        val end = if (neighbor.next>=0) chapterList.chapters[neighbor.next].position
                                  else durationMs
                        if (start<end) {
                            listOf(RangeMs(start, end))
                        } else {
                            emptyList()
                        }
                    }
                }
                if (ranges.isEmpty() && mode!= SaveMode.ALL) return null
                val rotation = model.playerModel.rotation.value
                val cropRect = if (model.cropHandler.maskViewModel.isCropped.value) model.cropHandler.maskViewModel.cropRect(size.width, size.height).asRect else null
                return VideoSourceInfoImpl(source, ranges, rotation, cropRect, null/*for future*/, positionMs, durationMs)
            }
        }
    }

    /**
     * 保存モード
     * ALL      ... すべての有効範囲を保存
     * LEFT     ... 現在の再生位置より前の有効範囲を保存
     * RIGHT    ... 現在の再生位置より後ろの有効範囲を保存
     * CHAPTER  ... 現在の再生位置を含むチャプターを保存
     */
    enum class SaveMode {
        ALL,
        LEFT,
        RIGHT,
        CHAPTER,        // カレントチャプター１つのみ
        CURRENT_RANGES,  // カレントチャプターを含む一続きの有効範囲
    }

    /**
     * 動画を保存する。
     *
     * 保存先ファイルの選択に使用する IOutputFileProvider は、以下の優先順序で使用する。
     *
     * 1. outputFileProvider 引数で渡された IOutputFileProvider
     * 2. MediaEditorModelコンストラクタで指定した outputFileProviderResolver から取得した IOutputFileProvider
     * 3. 上記どちらも設定されていなければ、ExportFileProvider
     *
     * @param mode 保存モード
     * @param outputFileProvider 保存先ファイル選択用
     * @return 保存に成功すれば true
     */
    open suspend fun saveVideo(mode:SaveMode, outputFileProvider:IOutputFileProvider?=null):Boolean {
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

    private fun resolveFileProvider(outputFileProvider:IOutputFileProvider?, item:IMediaSource) : IOutputFileProvider {
        return outputFileProvider ?: outputFileProviderResolver?.invoke(item) ?: ExportFileProvider("-EDITED")
    }
    private fun resolveFileSelector(outputFileSelector:IOutputFileSelector?, item:IMediaSource) : IOutputFileSelector {
        return outputFileSelector ?: outputFileSelectorResolver?.invoke(item) ?: ExportToDirectoryFileSelector()
    }

    /**
     * saveVideo(SaveMode.ALL) と同義
     */
    open suspend fun saveFile(outputFileProvider:IOutputFileProvider?=null):Boolean {
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

    /**
     * 分割モード
     * AT_POSITION ... 現在の再生位置で２つに分割
     * BY_CHAPTERS ... チャプターで分割
     */
    enum class SplitMode {
        AT_POSITION,
        BY_CHAPTERS,
    }

    /**
     * 動画ファイルを再生時間で分割する
     *
     * 保存先ファイルの選択に使用する IOutputSelectProvider は、以下の優先順序で使用する。
     *
     * 1. outputFileProvider 引数で渡された IOutputSelectorProvider
     * 2. MediaEditorModelコンストラクタで指定した outputFileSelectorResolver から取得した IOutputSelectorProvider
     * 3. 上記どちらも設定されていなければ、ExportToDirectoryFileSelector
     *
     * @param mode 保存モード
     * @param IOutputFileSelector 保存先ファイル選択用
     * @return 保存に成功すれば true
     */
    suspend fun splitVideo(mode:SplitMode, outputFileSelector: IOutputFileSelector?=null):Boolean {
        val item = playerModel.currentSource.value ?: return false
        if (item.isPhoto) return false
        savingNow.mutable.value = true
        return try {
            val sourceInfo = VideoSourceInfoImpl.fromModel(this) ?: return false
            val result = when (mode) {
                SplitMode.AT_POSITION -> splitHandler.splitAtCurrentPosition(sourceInfo, true, resolveFileSelector(outputFileSelector, item))
                SplitMode.BY_CHAPTERS -> splitHandler.splitByChapters(sourceInfo, true, resolveFileSelector(outputFileSelector, item))
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

    /**
     * リソース解放
     */
    override fun close() {
        playerControllerModel.close()
        cropHandler.dispose()
    }

    /**
     * ビルダークラス
     */
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
        @Suppress("unused")
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
        @Suppress("unused")
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
            val saveFileHandler = mSaveFileHandler ?: GenericSaveFileHandler(playerControllerModel.context, true)
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