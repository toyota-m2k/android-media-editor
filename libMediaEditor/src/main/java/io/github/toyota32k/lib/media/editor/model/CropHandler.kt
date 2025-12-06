package io.github.toyota32k.lib.media.editor.model

import android.widget.Button
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.observe
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.utils.toggle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

open class CropHandler(val playerModel: IPlayerModel, croppable: Boolean, showCompleteCancelButton:Boolean) : ICropHandler {
    override val croppable = MutableStateFlow<Boolean>(croppable)
    override val showCompleteCancelButton = MutableStateFlow<Boolean>(showCompleteCancelButton)
    override val croppingNow = MutableStateFlow<Boolean>(false)
    override val cropAspectMode get() = maskViewModel.aspectMode
    override val resolutionChangingNow = MutableStateFlow<Boolean>(false)
    override val canChangeResolution = MutableStateFlow<Boolean>(false)

    override val commandBeginCrop: IUnitCommand = LiteUnitCommand(::onBeginCrop)
    override val commandResetCrop: IUnitCommand = LiteUnitCommand(::onResetCrop)
    override val commandCancelCrop: IUnitCommand = LiteUnitCommand(::onCancelCrop)
    override val commandCompleteCrop: IUnitCommand = LiteUnitCommand(::onCompleteCrop)
    override val commandSetCropToMemory: IUnitCommand = LiteUnitCommand(::onSetCropToMemory)
    override val commandRestoreCropFromMemory: IUnitCommand = LiteUnitCommand(::onRestoreCropFromMemory)

    override val commandBeginResolutionChanging: IUnitCommand = LiteUnitCommand(::onBeginResolutionChanging)
    override val commandResetResolution: IUnitCommand = LiteUnitCommand(::onResetResolutionChanging)
    override val commandCancelResolutionChanging: IUnitCommand = LiteUnitCommand(::onCancelResolutionChanging)
    override val commandCompleteResolutionChanging: IUnitCommand = LiteUnitCommand(::onCompleteResolutionChanging)


    override val maskViewModel: CropMaskViewModel = CropMaskViewModel()
    override var cropImageModel: CropImageModel = CropImageModel(maskViewModel)

    override val sizeText: Flow<String> = combine(playerModel.isCurrentSourcePhoto, playerModel.videoSize, maskViewModel.cropFlows.cropWidth, maskViewModel.cropFlows.cropHeight, cropImageModel.bitmapScaler.bitmap) { isPhoto, videoSize, width, height, bmp ->
        if (isPhoto && bmp !=null) {
            "$width x $height (${bmp.width} x ${bmp.height})"
        } else if (videoSize!=null){
            "$width x $height (${videoSize.width} x ${videoSize.height})"
        } else ""
    }

    val storedCropParams = MutableStateFlow<MaskCoreParams?>(null)
    var originalCropParam = MaskCoreParams.IDENTITY

    override val isCropped: Flow<Boolean> get() = maskViewModel.isCropped
    override val isResolutionChanged: Flow<Boolean> = combine(canChangeResolution, cropImageModel.isResolutionChanged) { canChange, isChanged -> canChange && isChanged }
    override val isDirty: Boolean get() = maskViewModel.isCropped.value || (canChangeResolution.value && cropImageModel.isDirty)

    override fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>) {
        binder
            .observe(playerModel.shownBitmap) { bmp->
                canChangeResolution.value = bmp!=null
                cropImageModel.setSourceBitmap(bmp)
            }
            .observe(playerModel.videoSize) { size ->
                maskViewModel.updateCropFlow(size)
            }
        cropImageModel.bindView(binder, slider, minus, plus, presetButtons)
    }

    fun setAspectMode(mode: AspectMode) {
        cropAspectMode.value = mode
        maskViewModel.aspectMode.value = mode
    }

    open fun onBeginCrop() {
        if (!croppable.value) return
        croppingNow.value = true
        originalCropParam = maskViewModel.getParams()
    }
    open fun onResetCrop() {
        maskViewModel.resetCrop()
    }
    open fun onCancelCrop() {
        maskViewModel.setParams(originalCropParam)
        croppingNow.value = false
    }
    open fun onCompleteCrop() {
        croppingNow.value = false
    }
    open fun onSetCropToMemory() {
        storedCropParams.value = maskViewModel.getParams().run { if (isIdentity) null else this }
    }
    open fun onRestoreCropFromMemory() {
        maskViewModel.setParams(storedCropParams.value ?: return)
    }
    var oldResolutionValue:Float = 0f
    open fun onBeginResolutionChanging() {
        oldResolutionValue = cropImageModel.bitmapScaler.longSideLength.value
        resolutionChangingNow.value = true
        cropImageModel.bitmapScaler.enable()
    }
    open fun onCompleteResolutionChanging() {
        resolutionChangingNow.value = false
        cropImageModel.bitmapScaler.disable()
    }
    open fun onCancelResolutionChanging() {
        cropImageModel.bitmapScaler.longSideLength.value = oldResolutionValue
        resolutionChangingNow.value = false
        cropImageModel.bitmapScaler.disable()
    }
    open fun onResetResolutionChanging() {
        cropImageModel.bitmapScaler.resetResolution()
    }

    override fun cancelMode(): Boolean {
        if (croppingNow.value) {
            onCancelCrop()
            return true
        }
        if (resolutionChangingNow.value) {
            onCancelResolutionChanging()
            return true
        }
        return false
    }

    override fun dispose() {
        cropImageModel.dispose()
    }
}