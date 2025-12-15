package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.Button
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.lib.player.model.IChapterList
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IMediaSourceWithChapter
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.IMultiSplitResult
import io.github.toyota32k.media.lib.converter.IOutputFileSelector
import io.github.toyota32k.media.lib.converter.IOutputMediaFile
import io.github.toyota32k.media.lib.utils.RangeMs
import io.github.toyota32k.utils.IDisposable
import kotlinx.coroutines.flow.Flow

/**
 * 切り抜き編集用 Aspect定義
 */
enum class AspectMode(val label:String, val horizontal:Float, val vertical:Float) {
    FREE("Free", 0f, 0f),
    ASPECT_4_3("4:3", 4f, 3f),
    ASPECT_16_9("16:9", 16f, 9f),
    ASPECT_4_3_PORTRAIT("4:3 (P)", 3f,  vertical = 4f),
    ASPECT_16_9_PORTRAIT("16:9 (P)", 9f, vertical = 16f),
    // for wallpaper
    ASPECT_SCREEN_LANDSCAPE("Screen (L)", 0f, 0f),
    ASPECT_SCREEN_PORTRAIT("Screen (P)", 0f, vertical = 0f),
}

/**
 * 切り抜き操作用ハンドラーi/f
 */
interface ICropHandler : IDisposable {
    // region External View Models

    val maskViewModel: CropMaskViewModel
    var cropImageModel: CropImageModel

    // endregion

    // region States / Properties

    /**
     * Cropをサポートするか？
     */
    val croppable: Flow<Boolean>

    /**
     * EditControlPanelにCropのcomplete/cancelボタンを表示するか？
     * ダイアログで表示する場合などに false にして、モードの切り替えコマンドを外部から呼ぶ実装を行うために利用する。
     */
    val showCompleteCancelButton: Flow<Boolean>

    /**
     * 切り抜き中なら true
     */
    val croppingNow: Flow<Boolean>

    /**
     * 解像度変更中なら true
     */
    val resolutionChangingNow: Flow<Boolean>

    /**
     * 画像サイズをテキスト化
     */
    val sizeText: Flow<String>

    /**
     * 解像度の変更は可能か？
     * 写真編集中に true になる
     */
    val canChangeResolution: Flow<Boolean>

    /**
     * 切り抜く矩形に関する制約(AspectMode)
     */
    val cropAspectMode: Flow<AspectMode>

    /**
     * 切り抜きが設定されているか？
     */
    val isCropped: Flow<Boolean>

    /**
     * 解像度は変更されているか？
     */
    val isResolutionChanged: Flow<Boolean>

    /**
     * 変更されているか？
     */
    val isDirty:Boolean

    // endregion

    // region Commands (Cropping)

    val commandBeginCrop: IUnitCommand
    val commandCancelCrop: IUnitCommand
    val commandCompleteCrop: IUnitCommand
    val commandResetCrop: IUnitCommand
    val commandSetCropToMemory: IUnitCommand
    val commandRestoreCropFromMemory: IUnitCommand

    // endregion

    // region Commands (Resolution Changing)

    val commandBeginResolutionChanging: IUnitCommand
    val commandCompleteResolutionChanging: IUnitCommand
    val commandCancelResolutionChanging: IUnitCommand
    val commandResetResolution: IUnitCommand

    // endregion

    // region Methods

    /**
     * モデルにビューをバインドする
     * @param binder バインダ
     * @param slider 解像度調整用スライダー
     * @param minus 解像度調整用マイナスボタン
     * @param plus 解像度調整用プラスボタン
     * @param presetButtons プリセット解像度(長辺ピクセル数：720,1920...) と、対応するボタンのマップ
     */
    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons: Map<Int, Button>)

    /**
     * Cropping / ResolutionChanging モード中ならキャンセルする
     * @return true: キャンセルした
     *         false: キャンセルしなかった（どちらのモードでもなかった）
     */
    fun cancelMode():Boolean

    // endregion
}

/**
 * チャプター編集用ハンドラーi/f
 */
interface IChapterEditorHandler {
    // region States / Properties

    /**
     * チャプター編集機能を有効化する場合は true を設定
     */
    val chapterEditable: Flow<Boolean>

    /**
     * チャプターリストが編集されていれば true
     */
    val chapterListModified: Flow<Boolean>

    /**
     * チャプターリスト編集の Undo は可能か？
     */
    val canUndo: Flow<Boolean>

    /**
     * チャプターリスト編集の Undo は可能か？
     */
    val canRedo: Flow<Boolean>

    /**
     * チャプターリストは変更されているか？
     */
    val isDirty:Boolean

    // endregion

    // region Commands

    val commandAddChapter: IUnitCommand
    val commandAddSkippedChapterBefore: IUnitCommand
    val commandToggleSkipChapter: IUnitCommand
    val commandRemoveChapterBefore: IUnitCommand
    val commandRemoveChapterAfter: IUnitCommand
    val commandUndoChapter: IUnitCommand
    val commandRedoChapter: IUnitCommand

    // endregion

    fun getChapterList(): IChapterList

    /**
     * チャプターの編集結果を、「有効範囲のリスト」として取得する。
     * player.model.Range をMS単位で使用する。
     * @return 編集結果の有効範囲(Range)のリスト
     */
    fun getEnabledRangeList(): List<Range>
}

/**
 * 動画分割用ハンドラーi/f
 */
interface ISplitHandler {
    // region States / Properties

    /**
     * 分割機能を有効化する（Splitボタンを表示する）場合は true を設定
     */
    val showSplitButton: Flow<Boolean>

    /**
     * 分割処理完了イベントのリスナー登録
     */
    val listener: ISaveListener<ISourceInfo, IMultiSplitResult>

    /**
     * 現在のカーソル位置（再生位置）で２つのファイルに分割する
     */
    suspend fun splitAtCurrentPosition(sourceInfo:IVideoSourceInfo, optimize:Boolean, fileSelector: IOutputFileSelector): IMultiSplitResult?
    /**
     * チャプター毎に分割する。
     */
    suspend fun splitByChapters(sourceInfo:IVideoSourceInfo, optimize:Boolean, fileSelector: IOutputFileSelector): IMultiSplitResult?
}

/**
 * ファイル保存開始・完了イベントのリスナー i/f
 */
interface ISaveListener<S,E> {
    // 保存開始
    fun addOnSavingListener(fn:(S)->Unit): IDisposable
    fun addOnSavingListener(owner:LifecycleOwner, fn:(S)->Unit): IDisposable
    // 保存完了
    fun addOnSavedListener(fn:(E)->Unit): IDisposable
    fun addOnSavedListener(owner:LifecycleOwner, fn:(E)->Unit): IDisposable
}

// region Media Source i/f

/**
 * メディアソース情報基底i/f
 */
interface ISourceInfo {
    val source: IMediaSource
}

/**
 * 動画用メディアソース情報 i/f
 */
interface IVideoSourceInfo : ISourceInfo {
    val trimmingRanges: List<RangeMs>
    val rotation:Int /*in degree*/
    val cropRect:Rect?
    val brightness:Float?
    val positionMs: Long
    val durationMs: Long

    @Suppress("unused")
    val needsReEncoding get() = cropRect != null && brightness != null
}

/**
 * 画像用メディアソース情報 i/f
 */
interface IImageSourceInfo : ISourceInfo {
    val editedBitmap: Bitmap
}
// endregion

/**
 * メディアファイル保存用 i/f
 */
interface ISaveFileHandler {
    val showSaveButton: Flow<Boolean>   // ダイアログで使用する場合などにfalseにして、保存時には、MediaEditorModel#saveFile() を利用する
    val listener:ISaveListener<ISourceInfo, ISaveResult>
    suspend fun saveImage(sourceInfo: IImageSourceInfo, outputFileProvider: IOutputFileProvider):Boolean
    suspend fun saveVideo(sourceInfo:IVideoSourceInfo, outputFileProvider: IOutputFileProvider):Boolean
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
    @Suppress("unused")
    val cancelled:Boolean get() = status == Status.CANCELLED
    @Suppress("unused")
    val failed:Boolean get() = status == Status.ERROR

    val outputFile: IOutputMediaFile?
}

/**
 * 編集可能なチャプターリストを返す IMediaSource の拡張i/f
 */
interface IMediaSourceWithMutableChapterList : IMediaSourceWithChapter {
    override suspend fun getChapterList(): IMutableChapterList
}

/**
 * 保存先ファイル（書き込み可能なファイルのUri）を取得するための i/f
 */
interface IOutputFileProvider {
    suspend fun getOutputFile(mimeType: String, inputFile: AndroidFile): AndroidFile?
    fun finalize(succeeded: Boolean, inFile: AndroidFile, outFile: AndroidFile)
}

/**
 * IOutputFileProvider をファイル保存目的で汎用的に利用するための i/f
 * すべての FileProvider が汎用的に使える必要はないので、i/fを分離
 */
interface ICommonOutputFileProvider {
    suspend fun getOutputFile(mimeType: String, name: String): AndroidFile?
}
