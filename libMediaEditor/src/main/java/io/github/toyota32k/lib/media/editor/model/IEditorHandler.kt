package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.Button
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.media.lib.converter.Converter.Factory.RangeMs
import io.github.toyota32k.media.lib.converter.Rotation
import kotlinx.coroutines.flow.Flow

enum class AspectMode(val label:String, val longSide:Float, val shortSide:Float) {
    FREE("Free", 0f, 0f),
    ASPECT_4_3("4:3", 4f, 3f),
    ASPECT_16_9("16:9", 16f, 9f)
}

interface ICropHandler {
    val maskViewModel: CropMaskViewModel
    var cropImageModel: CropImageModel

    val croppable: Flow<Boolean>
    val croppingNow: Flow<Boolean>
    val resolutionChangingNow: Flow<Boolean>
    val canChangeResolution: Flow<Boolean>
    val cropAspectMode: Flow<AspectMode>
    val commandBeginCrop: IUnitCommand
    val commandSetAccept: IUnitCommand
    val commandCancelCrop: IUnitCommand
    val commandCompleteCrop: IUnitCommand
    val commandResetCrop: IUnitCommand
    val commandSetCropToMemory: IUnitCommand
    val commandRestoreCropFromMemory: IUnitCommand
    val commandStartResolutionChanging: IUnitCommand
    val commandCompleteResolutionChanging: IUnitCommand
    val commandCancelResolutionChanging: IUnitCommand

    val isCropped: Flow<Boolean>
    val isResolutionChanged: Flow<Boolean>

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>)
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
    val splittable: Flow<Boolean>
    suspend fun splitVideoAt(targetSource: IMediaSource, positionMs:Long)
}

interface ISaveFileHandler {
    suspend fun saveImage(newBitmap:Bitmap)
    suspend fun saveVideo(trimmingRanges:Array<RangeMs>?, rotation:Int/*degree*/, cropRect:Rect?, brightness:Float?)
}

interface IEditorHandler : ICropHandler, ISplitHandler, IChapterEditorHandler, ISaveFileHandler {
    val showMagnifyTimelineButton: Flow<Boolean>
    val commandMagnifyTimeline: IUnitCommand
    val commandSplitVideo: IUnitCommand
    val commandSaveFile: IUnitCommand
    val isDirty: Flow<Boolean>
}
