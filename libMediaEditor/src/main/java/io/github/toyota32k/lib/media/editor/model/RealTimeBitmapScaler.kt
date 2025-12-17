package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.widget.Button
import androidx.core.graphics.scale
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.BindingMode
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.enableBinding
import io.github.toyota32k.binder.sliderBinding
import io.github.toyota32k.utils.GenericDisposable
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.utils.IUtPropOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 画像の解像度変更操作用に、スケールされたビットマップをリアルタイムに生成するクラス
 */
class RealTimeBitmapScaler(val bitmapStore: BitmapStore): IUtPropOwner {
    companion object {
        const val MIN_LENGTH = 100f // px
    }
    private val busy = AtomicBoolean(false)
    private var scaledBitmap: Bitmap? = null
        set(v) { field = bitmapStore.replaceNullable(field, v) }

    private var scaledWidth:Int = 0
    private var scaledHeight:Int = 0
    val bitmap: StateFlow<Bitmap?> = MutableStateFlow(null)
    private val orgLongSideLength = MutableStateFlow(MIN_LENGTH+1f)
    val longSideLength = MutableStateFlow(0f)
    val isResolutionChanged = longSideLength.map {
        it != orgLongSideLength.value
    }
    val isDirty:Boolean get() = longSideLength.value != orgLongSideLength.value
    var tryAgain = false

    var sourceBitmap:Bitmap? = null
        private set

    private var enabled:Boolean = false

    fun enable(scopeForDeflateNow: CoroutineScope?=null) {
        enabled = true
        if (scopeForDeflateNow!=null && sourceBitmap != null) {
            scopeForDeflateNow.launch {
                deflateBitmap(longSideLength.value / orgLongSideLength.value)
            }
        }
    }
    fun disable() {
        enabled = false
    }

    fun setSourceBitmap(sourceBitmap:Bitmap?) {
        if (this.sourceBitmap == sourceBitmap) return

        bitmap.mutable.value = sourceBitmap
        this.sourceBitmap = sourceBitmap
        this.scaledBitmap = null
        if (sourceBitmap != null) {
            orgLongSideLength.value = max(sourceBitmap.width, sourceBitmap.height).toFloat()
            longSideLength.value = orgLongSideLength.value
            scaledWidth = sourceBitmap.width
            scaledHeight = sourceBitmap.height
        } else {
            orgLongSideLength.value = MIN_LENGTH+1f
            longSideLength.value = 0f
            scaledWidth = 0
            scaledHeight = 0
        }
    }

    fun resetResolution() {
        longSideLength.value = orgLongSideLength.value
    }


    private fun observeResolution(coroutineScope: CoroutineScope): IDisposable {
        val job = coroutineScope.launch {
            longSideLength.collect {
                deflateBitmap(it / orgLongSideLength.value)
            }
        }
        return GenericDisposable { job.cancel() }
    }

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>) {
        slider.stepSize = 1f
        val scope = binder.lifecycleOwner?.lifecycleScope ?: CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        // slider.valueTo = orgLongSideLength
        slider.valueFrom = MIN_LENGTH
        binder
            .sliderBinding(view=slider, data=longSideLength, mode= BindingMode.TwoWay, min=null, max=orgLongSideLength)
            .clickBinding(minus) {
                val len = ((longSideLength.value.roundToInt()+7)/8)*8 - 8
                longSideLength.value = len.toFloat().coerceAtLeast(MIN_LENGTH)
            }
            .clickBinding(plus) {
                val len = (longSideLength.value.roundToInt()/8)*8 + 8
                longSideLength.value = len.toFloat().coerceAtMost(orgLongSideLength.value)
            }
            .apply {
                for ((k, v) in presetButtons) {
                    clickBinding(v) {
                        longSideLength.value = k.toFloat()
                    }
                    enableBinding(v, orgLongSideLength.map { MIN_LENGTH*2<it && it.roundToInt() >= k })
                }
            }
            .add(observeResolution(scope))
    }

    private suspend fun deflateBitmap(newScale:Float) {
        if (!enabled) {
            scaledBitmap = null
            this.bitmap.mutable.value = null
            scaledWidth = 0
            scaledHeight = 0
            AmeGlobal.logger.debug("Scaler: longSideLength=${longSideLength.value}")
            return
        }
        val sourceBitmap = this.sourceBitmap ?: return
        if (!busy.getAndSet(true)) {
            var s = newScale
            try {
                while (true) {
                    tryAgain = false
                    val w = (sourceBitmap.width * s).toInt()
                    val h = (sourceBitmap.height * s).toInt()
                    if (w != scaledWidth || h != scaledHeight) {
                        if (w == sourceBitmap.width && h == sourceBitmap.height) {
                            scaledBitmap = null
                            bitmap.mutable.value = sourceBitmap
                        } else {
                            val bmp = withContext(Dispatchers.IO) { sourceBitmap.scale(w, h) }
                            bitmap.mutable.value = bmp
                            scaledBitmap = bmp
                        }
                        scaledWidth = w
                        scaledHeight = h
                    }
                    if (!tryAgain) break
                    s = longSideLength.value / orgLongSideLength.value
                }
            } finally {
                busy.set(false)
            }
        } else {
            tryAgain = true
        }
    }
}
