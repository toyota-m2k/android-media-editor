package io.github.toyota32k.media.editor.dialog

import android.Manifest
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.view.View
import androidx.annotation.RequiresPermission
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.combinatorialVisibilityBinding
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.observe
import io.github.toyota32k.binder.onViewSizeChanged
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.application
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.dialog.task.immortalTask
import io.github.toyota32k.dialog.task.showConfirmMessageBox
import io.github.toyota32k.lib.media.editor.handler.AndroidMediaFile.safeSaveBitmap
import io.github.toyota32k.lib.media.editor.handler.AndroidMediaFile.saveBitmap
import io.github.toyota32k.lib.media.editor.handler.ExportFileProvider
import io.github.toyota32k.lib.media.editor.handler.MediaFileProvider
import io.github.toyota32k.lib.media.editor.model.BitmapStore
import io.github.toyota32k.lib.media.editor.model.CropMaskViewModel
import io.github.toyota32k.lib.media.editor.model.MaskCoreParams
import io.github.toyota32k.lib.media.editor.model.RealTimeBitmapScaler
import io.github.toyota32k.lib.media.editor.view.EditorControlPanel
import io.github.toyota32k.media.editor.MainActivity
import io.github.toyota32k.media.editor.databinding.DialogSnapshotBinding
import io.github.toyota32k.media.editor.dialog.WallpaperDialog.WallpaperViewModel.FileStore
import io.github.toyota32k.utils.Disposer
import io.github.toyota32k.utils.android.FitMode
import io.github.toyota32k.utils.android.UtFitter
import io.github.toyota32k.utils.android.dp2px
import io.github.toyota32k.utils.android.setLayoutSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

class SnapshotDialog : UtDialogEx() {
    class SnapshotViewModel : UtDialogViewModel() {
        var defaultFileName:String = "image"
        val bitmapStore = BitmapStore()
        lateinit var bitmapScaler: RealTimeBitmapScaler
        val deflating = MutableStateFlow(false)
        val croppedBitmapFlow = MutableStateFlow<Bitmap?>(null)
        var cropBitmap: Bitmap?
            get() = croppedBitmapFlow.value
            set(v) {
                croppedBitmapFlow.value = bitmapStore.replaceNullable(croppedBitmapFlow.value, v)
            }
        val isCropped = croppedBitmapFlow.map { it != null }
        val croppingNow = MutableStateFlow(true)
        val maskViewModel = CropMaskViewModel()
        private val cropFlows = maskViewModel.cropFlows

        var result: CropResult? = null

        data class CropResult(
            val bitmap: Bitmap,
            val maskParams: MaskCoreParams?
        )

        private val croppedSize =
            combine(croppingNow, croppedBitmapFlow, cropFlows.cropWidth, cropFlows.cropHeight) { trimmingNow, cropped, w, h ->
                if (trimmingNow) {
                    "$w x $h"
                } else if (cropped!=null) {
                    "${cropped.width} x ${cropped.height}"
                } else {
                    null
                }
            }
        private val baseSize by lazy {
            bitmapScaler.bitmap.map {
                if (it!=null) "${it.width} x ${it.height}" else ""
            }
        }

        val sizeText by lazy {
            combine(croppedSize, baseSize) { cropped, base ->
                if (cropped!=null) {
                    "$cropped  ($base)"
                } else {
                    base
                }
            }
        }

        private val disposer = Disposer()
        fun setup(bitmap: Bitmap, initialName:String, autoRecycle:Boolean, maskParams: MaskCoreParams?): SnapshotViewModel {
            bitmapScaler = RealTimeBitmapScaler(bitmapStore)
            bitmapScaler.setSourceBitmap(bitmap)
            if (autoRecycle) {
                bitmapStore.attach(bitmap)
            }
            if (maskParams!=null) {
                maskViewModel.setParams(maskParams)
            }
            defaultFileName = initialName
            maskViewModel.updateCropFlow(Size(bitmap.width,bitmap.height))
            disposer.register(
                bitmapStore,
//                bitmapScaler.apply {start(viewModelScope)},
//                bitmapScaler.bitmap.disposableObserve {
//                    maskViewModel.enableCropFlow(it.width, it.height)
//                }
            )
            return this
        }

//        fun crop(): Bitmap? {
//            val src = bitmapScaler.bitmap.value ?: return null
//            return maskViewModel.cropBitmap(src).also {
//                cropBitmap = it
//            }
//        }
//
//        fun fix():Boolean {
//            val bitmap = cropBitmap ?: bitmapScaler.bitmap.value ?: return false
//            bitmapStore.detach(bitmap)
//            result = CropResult(
//                bitmap = bitmap,
//                maskParams = maskViewModel.getParams()
//            )
//            return true
//        }

        @RequiresPermission(Manifest.permission.SET_WALLPAPER)
        fun setWallpaper(bitmap: Bitmap, setLockScreen: Boolean, setHomeScreen: Boolean, hintRect:Rect?):Boolean {
            try {
                val wallpaperManager = WallpaperManager.getInstance(application)
                val flags = (if (setLockScreen) WallpaperManager.FLAG_LOCK else 0) or
                        (if (setHomeScreen) WallpaperManager.FLAG_SYSTEM else 0)
                if(flags == 0) return false
                wallpaperManager.setBitmap(bitmap, hintRect, true, flags)
                return true
            } catch (e: Throwable) {
                logger.error(e)
                return false
            }
        }

        private suspend fun cropAndSaveBitmap(vm:WallpaperDialog.WallpaperViewModel, srcBitmap: Bitmap):Boolean {
            if (vm.target.value == WallpaperDialog.WallpaperViewModel.Target.WALLPAPER && vm.useCropHint.value) {
                val cropRect = maskViewModel.cropRect(srcBitmap)
                return setWallpaper(srcBitmap, vm.lockScreen.value, vm.homeScreen.value, cropRect.asRect)
            }
            val bitmap = maskViewModel.cropBitmap(srcBitmap)
            return saveRawBitmap(vm, bitmap)
        }

//        private suspend fun saveImageAsFile(bitmap:Bitmap, activity: MainActivity):Boolean? {
//            val uri = activity.activityBrokers.createFilePicker.selectFile(defaultFileName, "image/jpeg") ?: return null
//            return try {
//                activity.contentResolver.openOutputStream(uri)?.use {
//                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
//                    it.flush()
//                    true
//                } ?: false
//            } catch(e:Exception) {
//                false
//            }
//        }
//
        private suspend fun saveRawBitmap(vm:WallpaperDialog.WallpaperViewModel, bitmap: Bitmap):Boolean {
            return when (vm.target.value) {
                WallpaperDialog.WallpaperViewModel.Target.WALLPAPER -> {
                    setWallpaper(bitmap, vm.lockScreen.value, vm.homeScreen.value, null)
                }
                WallpaperDialog.WallpaperViewModel.Target.FILE -> {
                    val outputFile = when (vm.fileStore.value) {
                        FileStore.SAVE_FILE -> ExportFileProvider("")
                        FileStore.SAVE_IN_ALBUM -> MediaFileProvider("")
                    }.getOutputFile("image/jpeg", defaultFileName) ?: return false
                    outputFile.safeSaveBitmap(bitmap, Bitmap.CompressFormat.JPEG, 100)
                }
            }
        }

        val complete = LiteUnitCommand()

        fun saveBitmap() {
            val bitmap = cropBitmap ?: bitmapScaler.bitmap.value ?: throw IllegalStateException("no bitmap")
            bitmapStore.detach(bitmap)
            launchSubTask {
                val vm = createViewModel<WallpaperDialog.WallpaperViewModel> { displayName.value = defaultFileName }
                if (showDialog(WallpaperDialog::class.java.name) { WallpaperDialog() }.status.ok) {
                    val saved = if (maskViewModel.isCropped.value) {
                        cropAndSaveBitmap(vm, bitmap)
                    } else {
                        saveRawBitmap(vm, bitmap)
                    }
                    if (!saved) {
                        showConfirmMessageBox("Snapshot", "Failed to save snapshot")
                    } else {
                        complete.invoke()
                    }
                }
            }

        }

        override fun onCleared() {
            super.onCleared()
            disposer.dispose()
        }

    }

    override fun preCreateBodyView() {
        cancellable = false
        heightOption = HeightOption.FULL
        widthOption = WidthOption.FULL
//        noHeader = true
        title = "Snapshot"
        leftButtonType = ButtonType.CANCEL
        rightButtonType = ButtonType.OK
    }
    private val viewModel: SnapshotViewModel by lazy { getViewModel() }
    private lateinit var controls: DialogSnapshotBinding

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogSnapshotBinding.inflate(inflater.layoutInflater, null, false)
        controls.maskView.bindViewModel(binder, viewModel.maskViewModel)
//        controls.image.setImageBitmap(viewModel.targetBitmap)
        binder
            .owner(this)
            .textBinding(controls.sizeText, viewModel.sizeText)
            .textBinding(controls.aspectButton, viewModel.maskViewModel.aspectMode.map { it.label })
//            .visibilityBinding(controls.resolutionPanel, viewModel.deflating)
//            .enableBinding(controls.memoryRead, viewModel.maskViewModel.memory.map { it!=null }, BoolConvert.Straight, alphaOnDisabled=0.4f)
//            .dialogLeftButtonString(viewModel.trimmingNow.map { if(it) getString(R.string.cancel) else getString(R.string.reject) })
//            .dialogRightButtonString(viewModel.trimmingNow.map { if(it) getString(R.string.crop) else getString(R.string.accept) })
            .combinatorialVisibilityBinding(viewModel.isCropped) {
                inverseGone(controls.image)
                straightGone(controls.imagePreview)
            }
            .combinatorialVisibilityBinding(viewModel.croppingNow) {
                straightGone(controls.maskView,controls.aspectButton, controls.maxButton,)
//                inverseGone(optionButton!!)
            }
//            .apply {
//                viewModel.bitmapScaler.bindToSlider(this, controls.resolutionSlider, controls.buttonMinus, controls.buttonPlus,
//                    mapOf(480 to controls.button480, 720 to controls.button720, 1280 to controls.button1280, 1920 to controls.button1920))
//            }
            .clickBinding(controls.maxButton) {
                viewModel.maskViewModel.resetCrop()
                controls.maskView.invalidateIfNeed()
            }
            .clickBinding(controls.aspectButton) {
                lifecycleScope.launch {
                    val aspect = EditorControlPanel.popupAspectMenu(context, it, supportScreenAspect = true)
                    if(aspect!=null) {
                        viewModel.maskViewModel.aspectMode.value = aspect
                    }
                }
            }
            .bindCommand(viewModel.complete) {
                super.onPositive()
            }
//            .clickBinding(controls.memoryPlus) {
//                viewModel.maskViewModel.pushMemory()
//            }
//            .clickBinding(controls.memoryRead) {
//                controls.maskView.applyCropFromMemory()
//            }
//            .clickBinding(controls.resolutionButton) {
//                viewModel.deflating.value = !viewModel.deflating.value
//            }
//            .clickBinding(optionButton!!) {
//                viewModel.deflating.value = false
//                viewModel.cropBitmap = null
//                viewModel.trimmingNow.value = true
//            }
//            .clickBinding(leftButton) {
//                if (viewModel.croppingNow.value) {
//                    viewModel.croppingNow.value = false
//                } else {
//                    onNegative()
//                }
//            }
//            .clickBinding(rightButton) {
//                if (viewModel.croppingNow.value) {
//                    // crop
//                    viewModel.crop()
//                    viewModel.croppingNow.value = false
//                } else {
//                    // accept --> detatch cropped bitmap to result
//                    viewModel.fix()
//                    onPositive()
//                }
//            }
            .observe(viewModel.croppedBitmapFlow)  { bmp->
                controls.imagePreview.setImageBitmap(bmp)
            }
            .observe(viewModel.bitmapScaler.bitmap) {
                controls.image.setImageBitmap(it)
                if (it!=null) {
                    fitBitmap(it, controls.mainContainer.width, controls.mainContainer.height)
                }
            }
            .onViewSizeChanged(controls.mainContainer) { w, h ->
                val bitmap = viewModel.bitmapScaler.bitmap.value ?: return@onViewSizeChanged
                fitBitmap(bitmap, w, h)
            }
            .observe(viewModel.croppingNow) { cropping->
                controls.maskView.showHandle(cropping)
                val padding = if (cropping) {
                    context.dp2px(16)
                } else 0
                controls.maskView.setPadding(padding)
                controls.imagePreview.setPadding(padding)
                controls.image.setPadding(padding)
            }

        return controls.root
    }

    private fun fitBitmap(bitmap:Bitmap, containerWidth:Int, containerHeight:Int) {
//        val paddingHorizontal = controls.image.paddingLeft + controls.image.paddingRight
//        val paddingVertical = controls.image.paddingTop + controls.image.paddingBottom
        // image/image_preview/maskView には同じ padding が設定されている
        // コンテナー領域から、そのpaddingを差し引いた領域内に、bitmapを最大表示したときのサイズを計算
        val w = containerWidth // - paddingHorizontal
        val h = containerHeight // - paddingVertical
        val fitter = UtFitter(FitMode.Inside, w, h)
        val size = fitter.fit(bitmap.width, bitmap.height).result.asSize
        // bitmapのサイズに padding を加えたサイズを imageContainerにセットする。
        controls.imageContainer.setLayoutSize(size.width, size.height)
    }

    override fun onDialogClosing() {
        controls.image.setImageBitmap(null)
        controls.imagePreview.setImageBitmap(null)
        super.onDialogClosing()
    }

    override fun onPositive() {
        // super.onPositive()
        viewModel.saveBitmap()
    }

    companion object {
        suspend fun showBitmap(
            source: Bitmap,
            initialName: String,
            autoRecycle:Boolean = true,
            maskParams: MaskCoreParams?=null): SnapshotViewModel.CropResult? {
            return UtImmortalTask.awaitTaskResult(this::class.java.name) {
                val vm = createViewModel<SnapshotViewModel> { setup(source, initialName, autoRecycle, maskParams) }
                if (showDialog(taskName) { SnapshotDialog() }.status.ok) {
                    vm.result
                } else {
                    null
                }
            }
        }
    }
}

