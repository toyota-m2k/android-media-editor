package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.widget.Button
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.utils.IDisposable
import kotlinx.coroutines.flow.Flow

/**
 * 画像切り抜き / 解像度変更操作用 ViewModel
 */
class CropImageModel(val maskViewModel: CropMaskViewModel): IDisposable {
    val bitmapStore = BitmapStore()
    val bitmapScaler = RealTimeBitmapScaler(bitmapStore)
    val isResolutionChanged: Flow<Boolean> get() = bitmapScaler.isResolutionChanged
    val isDirty:Boolean get() = bitmapScaler.isDirty

    fun setSourceBitmap(sourceBitmap: Bitmap?) {
        bitmapScaler.setSourceBitmap(sourceBitmap)
    }

    fun crop(): Bitmap? {
        val bitmap = bitmapScaler.bitmap.value ?: bitmapScaler.sourceBitmap ?: return null
        return bitmapStore.detach(maskViewModel.cropBitmap(bitmap))
    }

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>) {
        bitmapScaler.bindView(binder, slider, minus, plus, presetButtons)
    }

    override fun dispose() {
        bitmapStore.dispose()
    }
}