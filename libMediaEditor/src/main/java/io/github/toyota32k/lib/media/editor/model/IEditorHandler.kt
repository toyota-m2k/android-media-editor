package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.Button
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IMediaSourceWithChapter
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.media.lib.converter.Converter.Factory.RangeMs
import io.github.toyota32k.utils.IDisposable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

enum class AspectMode(val label:String, val longSide:Float, val shortSide:Float) {
    FREE("Free", 0f, 0f),
    ASPECT_4_3("4:3", 4f, 3f),
    ASPECT_16_9("16:9", 16f, 9f)
}

interface ICropHandler : IDisposable {
    val croppable: Flow<Boolean>                // Cropをサポートするか？
    val showCompleteCancelButton: Flow<Boolean>  // ダイアログで表示する場合などに false にして、モードの切り替えコマンドを自力で呼ぶ

    val maskViewModel: CropMaskViewModel
    var cropImageModel: CropImageModel

    val croppingNow: Flow<Boolean>
    val resolutionChangingNow: Flow<Boolean>
    val canChangeResolution: Flow<Boolean>
    val cropAspectMode: Flow<AspectMode>
    val commandBeginCrop: IUnitCommand
    val commandCancelCrop: IUnitCommand
    val commandCompleteCrop: IUnitCommand
    val commandResetCrop: IUnitCommand
    val commandSetCropToMemory: IUnitCommand
    val commandRestoreCropFromMemory: IUnitCommand
    val commandToggleResolutionChanging: IUnitCommand

    val isCropped: Flow<Boolean>
    val isResolutionChanged: Flow<Boolean>

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons: Map<Int, Button>)
}

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
    fun getEnabledRangeList(): List<Range>
}

interface ISplitHandler {
    val showSplitButton: Flow<Boolean>
    suspend fun splitVideoAt(targetSource: IMediaSource, positionMs:Long):Boolean
}

abstract class AbstractSplitHandler(supportSplitting:Boolean) : ISplitHandler {
    override val showSplitButton = MutableStateFlow(supportSplitting)
}

object NoopSplitHandler : ISplitHandler {
    override val showSplitButton: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun splitVideoAt(targetSource: IMediaSource, positionMs: Long):Boolean { return false }
}

interface ISaveFileHandler {
    val showSaveButton: Flow<Boolean>   // ダイアログで使用する場合などにfalseにして、保存時には、MediaEditorModel#saveFile() を利用する
    suspend fun saveImage(newBitmap:Bitmap):Boolean
    suspend fun saveVideo(trimmingRanges:Array<RangeMs>?, rotation:Int/*degree*/, cropRect:Rect?, brightness:Float?):Boolean
}

abstract class AbstractSaveFileHandler(showSaveButton:Boolean) : ISaveFileHandler {
    override val showSaveButton = MutableStateFlow(showSaveButton)
}

interface IMediaSourceWithMutableChapterList : IMediaSourceWithChapter {
    override suspend fun getChapterList(): IMutableChapterList
}