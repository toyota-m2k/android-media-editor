package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.Button
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.lib.media.editor.handler.save.ISaveResult
import io.github.toyota32k.lib.media.editor.handler.save.ISavedListener
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IMediaSourceWithChapter
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.IMultiSplitResult
import io.github.toyota32k.media.lib.converter.IOutputFileSelector
import io.github.toyota32k.media.lib.converter.RangeMs
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
}

/**
 * 切り抜き操作用ハンドラーi/f
 */
interface ICropHandler : IDisposable {
    val croppable: Flow<Boolean>                // Cropをサポートするか？
    val showCompleteCancelButton: Flow<Boolean>  // ダイアログで表示する場合などに false にして、モードの切り替えコマンドを自力で呼ぶ

    val maskViewModel: CropMaskViewModel
    var cropImageModel: CropImageModel

    val croppingNow: Flow<Boolean>
    val resolutionChangingNow: Flow<Boolean>
    val sizeText: Flow<String>

    val canChangeResolution: Flow<Boolean>
    val cropAspectMode: Flow<AspectMode>
    val commandBeginCrop: IUnitCommand
    val commandCancelCrop: IUnitCommand
    val commandCompleteCrop: IUnitCommand
    val commandResetCrop: IUnitCommand
    val commandSetCropToMemory: IUnitCommand
    val commandRestoreCropFromMemory: IUnitCommand

    val commandBeginResolutionChanging: IUnitCommand
    val commandCompleteResolutionChanging: IUnitCommand
    val commandCancelResolutionChanging: IUnitCommand
    val commandResetResolution: IUnitCommand

    val isCropped: Flow<Boolean>
    val isResolutionChanged: Flow<Boolean>
    val isDirty:Boolean

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons: Map<Int, Button>)
}

/**
 * チャプター編集用ハンドラーi/f
 */
interface IChapterEditorHandler {
    val chapterEditable: Flow<Boolean>
    val commandAddChapter: IUnitCommand
    val commandAddSkippedChapterBefore: IUnitCommand
    val commandToggleSkipChapter: IUnitCommand
    val commandRemoveChapterBefore: IUnitCommand
    val commandRemoveChapterAfter: IUnitCommand
    val commandUndoChapter: IUnitCommand
    val commandRedoChapter: IUnitCommand
    val chapterListModified: Flow<Boolean>
    val canUndo: Flow<Boolean>
    val canRedo: Flow<Boolean>
    val isDirty:Boolean
    fun getEnabledRangeList(): List<Range>
}

/**
 * 動画分割用ハンドラーi/f
 */
interface ISplitHandler {
    val showSplitButton: Flow<Boolean>
    val listener: ISavedListener<IMultiSplitResult>
    suspend fun splitAtCurrentPosition(sourceInfo:IVideoSourceInfo, optimize:Boolean, fileSelector: IOutputFileSelector): IMultiSplitResult?
    suspend fun splitByChapters(sourceInfo:IVideoSourceInfo, optimize:Boolean, fileSelector: IOutputFileSelector): IMultiSplitResult?
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
    val trimmingRanges:List<RangeMs>
    val rotation:Int/*in degree*/
    val cropRect:Rect?
    val brightness:Float?
    val positionMs: Long
    val durationMs: Long

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
    val listener:ISavedListener<ISaveResult>
    suspend fun saveImage(sourceInfo: IImageSourceInfo, outputFileProvider: IOutputFileProvider):Boolean
    suspend fun saveVideo(sourceInfo:IVideoSourceInfo, outputFileProvider: IOutputFileProvider):Boolean
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
