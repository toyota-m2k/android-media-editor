package io.github.toyota32k.lib.media.editor.model

import android.widget.Button
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.utils.android.RefBitmap
import kotlinx.coroutines.flow.Flow

/**
 * 画像切り抜き / 解像度変更操作用 ViewModel
 */
class CropImageModel(val maskViewModel: CropMaskViewModel): IDisposable {
//    val bitmapStore = BitmapStore()
    val bitmapScaler = RealTimeBitmapScaler()
    val isResolutionChanged: Flow<Boolean> get() = bitmapScaler.isResolutionChanged
    val isDirty:Boolean get() = bitmapScaler.isDirty

    fun setSourceBitmap(sourceBitmap: RefBitmap?) {
        bitmapScaler.setSource(sourceBitmap)
    }

    fun crop(): RefBitmap? {
        val src = bitmapScaler.bitmap.value ?: bitmapScaler.sourceBitmap ?: return null
        return maskViewModel.cropBitmap(src)
    }

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>) {
        bitmapScaler.bindView(binder, slider, minus, plus, presetButtons)
    }

    override fun dispose() {
        bitmapScaler.dispose()
    }
}