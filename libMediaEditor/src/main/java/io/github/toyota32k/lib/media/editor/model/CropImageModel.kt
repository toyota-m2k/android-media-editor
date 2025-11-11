package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.widget.Button
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.observe
import io.github.toyota32k.utils.IDisposable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class CropImageModel(val maskViewModel: CropMaskViewModel): IDisposable {
    val bitmapStore = BitmapStore()
    val bitmapScaler = RealTimeBitmapScaler(bitmapStore)
//    val targetBitmap:Flow<Bitmap?> get() = bitmapScaler.bitmap
//    private val cropFlows: CropMaskViewModel.ICropFlows get() =  maskViewModel.cropFlows
    val isResolutionChanged: Flow<Boolean> get() = bitmapScaler.isResolutionChanged
    val isDirty:Boolean get() = bitmapScaler.isDirty

    fun setSourceBitmap(sourceBitmap: Bitmap?) {
        bitmapScaler.setSourceBitmap(sourceBitmap)
    }

    fun crop(): Bitmap? {
        val bitmap = bitmapScaler.bitmap.value ?: return null
        return bitmapStore.detach(maskViewModel.cropBitmap(bitmap))
    }

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>) {
        bitmapScaler.bindView(binder, slider, minus, plus, presetButtons)
//        binder.observe(bitmapScaler.bitmap) {
//            if (it!=null) {
//                maskViewModel.startCropFlow(it.width, it.height)
//            }
//        }
    }

    override fun dispose() {
        bitmapStore.dispose()
    }
}