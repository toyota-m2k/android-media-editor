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
import io.github.toyota32k.utils.lifecycle.ConstantLiveData
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

class RealTimeBitmapScaler(val bitmapStore: BitmapStore): IUtPropOwner {
    companion object {
        const val MIN_LENGTH = 100f // px
    }
    private val busy = AtomicBoolean(false)
    private var scaledBitmap: Bitmap? = null
        get() = field
        set(v) { field = bitmapStore.replaceNullable(field, v) }

    private var scaledWidth:Int = 0
    private var scaledHeight:Int = 0
    val bitmap: StateFlow<Bitmap?> = MutableStateFlow<Bitmap?>(null)
    var orgLongSideLength:Float = 0f // = max(sourceBitmap.width, sourceBitmap.height).toFloat()
    val longSideLength = MutableStateFlow(0f)
    val isResolutionChanged = longSideLength.map { it != orgLongSideLength }
    var tryAgain = false

    private var sourceBitmap:Bitmap? = null

    fun setSourceBitmap(sourceBitmap:Bitmap?) {
        if (this.sourceBitmap == sourceBitmap) return

        bitmap.mutable.value = sourceBitmap
        this.sourceBitmap = sourceBitmap
        this.scaledBitmap = null
        if (sourceBitmap != null) {
            orgLongSideLength = max(sourceBitmap.width, sourceBitmap.height).toFloat()
            longSideLength.value = orgLongSideLength
            scaledWidth = sourceBitmap.width
            scaledHeight = sourceBitmap.height
        } else {
            orgLongSideLength = 0f
            longSideLength.value = 0f
            scaledWidth = 0
            scaledHeight = 0
        }
    }


    private fun observeResolution(coroutineScope: CoroutineScope): IDisposable {
        val job = coroutineScope.launch {
            longSideLength.collect {
                deflateBitmap(it / orgLongSideLength)
            }
        }
        return GenericDisposable { job.cancel() }
    }

    fun bindView(binder: Binder, slider: Slider, minus: Button, plus: Button, presetButtons:Map<Int, Button>) {
        slider.stepSize = 1f
        val scope = binder.lifecycleOwner?.lifecycleScope ?: CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        binder
            .sliderBinding(view=slider, data=longSideLength, mode= BindingMode.TwoWay, min= MutableStateFlow<Float>(MIN_LENGTH), max= MutableStateFlow(orgLongSideLength))
            .clickBinding(minus) {
                val len = ((longSideLength.value.roundToInt()+7)/8)*8 - 8
                longSideLength.value = len.toFloat().coerceAtLeast(MIN_LENGTH)
            }
            .clickBinding(plus) {
                val len = (longSideLength.value.roundToInt()/8)*8 + 8
                longSideLength.value = len.toFloat().coerceAtMost(orgLongSideLength)
            }
            .apply {
                for ((k, v) in presetButtons) {
                    clickBinding(v) {
                        longSideLength.value = k.toFloat()
                    }
                    enableBinding(v, ConstantLiveData( MIN_LENGTH*2<orgLongSideLength && orgLongSideLength.roundToInt() >= k))
                }
            }
            .add(observeResolution(scope))
    }

    private suspend fun deflateBitmap(newScale:Float) {
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
                    s = longSideLength.value / orgLongSideLength
                }
            } finally {
                busy.set(false)
            }
        } else {
            tryAgain = true
        }
    }
}
