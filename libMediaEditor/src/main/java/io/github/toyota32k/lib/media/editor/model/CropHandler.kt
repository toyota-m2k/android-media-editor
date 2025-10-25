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
    override val commandToggleResolutionChanging: IUnitCommand = LiteUnitCommand(::onToggleResolutionChanging)


    override val maskViewModel: CropMaskViewModel = CropMaskViewModel()
    override var cropImageModel: CropImageModel = CropImageModel(maskViewModel)

    val storedCropParams = MutableStateFlow<MaskCoreParams?>(null)
    var originalCropParam = MaskCoreParams.IDENTITY

    override val isCropped: Flow<Boolean> get() = maskViewModel.isCropped
    override val isResolutionChanged: Flow<Boolean> = combine(canChangeResolution, cropImageModel.isResolutionChanged) { canChange, isChanged -> canChange && isChanged }

    override fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>) {
        cropImageModel.bindView(binder, slider, minus, plus, presetButtons)
        binder
            .observe(playerModel.shownBitmap) { bmp->
                canChangeResolution.value = bmp!=null
                cropImageModel.setSourceBitmap(bmp)
            }
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
    open fun onToggleResolutionChanging() {
        resolutionChangingNow.toggle()
    }

    override fun dispose() {
        cropImageModel.dispose()
    }
}