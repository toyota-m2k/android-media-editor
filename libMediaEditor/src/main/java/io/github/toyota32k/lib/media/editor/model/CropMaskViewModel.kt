package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.lib.media.editor.view.CropMaskView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CropMaskView の位置情報を表すデータクラス
 *
 * rsx, rsy: mask の左上の位置 (0.0～1.0)
 * rex, rey: mask の右下の位置 (0.0～1.0)
 */
data class MaskCoreParams(
    val rsx:Float,
    val rsy:Float,
    val rex:Float,
    val rey:Float,
) {
    companion object {
        @Suppress("unused")
        fun fromSize(sourceWidth:Int, sourceHeight:Int, sx:Float, sy:Float, w:Float, h:Float):MaskCoreParams {
            return MaskCoreParams(
                (sx / sourceWidth.toFloat()).coerceIn(0f, 1f),
                (sy / sourceHeight.toFloat()).coerceIn(0f, 1f),
                ((sx+w) / sourceWidth.toFloat()).coerceIn(0f, 1f),
                ((sy+h) / sourceHeight.toFloat()).coerceIn(0f, 1f),
            )
        }
        val IDENTITY:MaskCoreParams = MaskCoreParams(0f, 0f, 1f, 1f)
        fun fromJson(json:String?):MaskCoreParams {
            if (json==null) return IDENTITY
            val obj = JSONObject(json)
            return MaskCoreParams(
                obj.getDouble("rsx").toFloat(),
                obj.getDouble("rsy").toFloat(),
                obj.getDouble("rex").toFloat(),
                obj.getDouble("rey").toFloat(),
            )
        }
    }
    val isIdentity:Boolean get() {
        return rsx==0f && rsy==0f && rex==1f && rey==1f
    }
    fun serialize():String {
        return JSONObject().apply {
            put("rsx", rsx)
            put("rsy", rsy)
            put("rex", rex)
            put("rey", rey)
        }.toString()
    }
}

/**
 * CropMask 用の ViewModel
 */
class CropMaskViewModel {
    companion object {
        //        const val MIN = 32f
        val logger = UtLog("CropMaskView", null, CropMaskView::class.java)
        var previousAspectMode = AspectMode.FREE
//        var memorizedCoreParams:MaskCoreParams? = null
    }

    // invalidateが必要かどうか
    var isDirty: Boolean = false
    var aspectMode = MutableStateFlow(previousAspectMode)
//    var memory:StateFlow<MaskCoreParams?> = MutableStateFlow(memorizedCoreParams)
//    fun pushMemory() {
//        val p = getParams()
//        (memory as MutableStateFlow<MaskCoreParams?>).value = p
//        memorizedCoreParams = p
//    }

    // isDirty が true の場合に fn() を実行し、isDirty を false にする
    // usage: viewModel.clearDirty { invalidate() }
    fun clearDirty(fn:()->Unit) {
        if(isDirty) {
            fn()
            isDirty = false
            cropFlows.update()
        }
    }
    // Mask用位置データ
    // 0.0～1.0 の範囲で、ソース画像に対する相対位置を表す
    // これがコアデータで、そのほかのパラメータはこの値から導出する。
    private var rsx:Float = 0f
        set(v) {
            if(v!=field) {
                field = v
                isDirty = true
            }
        }
    private var rsy:Float = 0f
        set(v) {
            if(v!=field) {
                field = v
                isDirty = true
            }
        }

    private var rex:Float = 1f
        set(v) {
            if(v!=field) {
                field = v
                isDirty = true
            }
        }
    private var rey:Float = 1f
        set(v) {
            if(v!=field) {
                field = v
                isDirty = true
            }
        }

    // view の padding (mask のハンドルの半径に相当)
    var padding:Int = 0
        private set(v) {
            val nv = v.coerceAtLeast(0)
            if(nv!=field) {
                field = nv
                isDirty = true
            }
        }

    // padding 込みの view サイズ
    var rawViewWidth:Int = 0
        private set(v) {
            if(v!=field) {
                field = v
                isDirty = true
            }
        }
    var rawViewHeight:Int = 0
        private set(v) {
            if(v!=field) {
                field = v
                isDirty = true
            }
        }

    fun resetCrop() {
        rsx = 0f
        rsy = 0f
        rex = 1f
        rey = 1f

    }

    // view のサイズと padding を設定する
    fun setViewDimension(width:Int, height:Int, padding:Int) {
        this.rawViewWidth = width
        this.rawViewHeight = height
        this.padding = padding
    }

    // padding を除いた view サイズ
    val viewSizeAvailable:Boolean get() = rawViewWidth > 0 && rawViewHeight > 0
    val viewWidth:Int
        get() = (rawViewWidth - padding*2).coerceAtLeast(1)
    val viewHeight:Int
        get() = (rawViewHeight - padding*2).coerceAtLeast(1)
    val minMaskWidth:Float
        get() = padding * 2f

    // View上での mask の位置 (px 単位、padding 込み)
    var maskSx:Float
        get() = rsx * viewWidth + padding
        private set(v) { rsx = (v-padding) / viewWidth }
    private fun correctSx(v:Float):Float = v.coerceIn(padding.toFloat(), maskEx-minMaskWidth+padding)
    fun setMaskSx(v:Float):Float {
        return correctSx(v).also { maskSx = it }
    }

    val minX:Float get() = padding.toFloat()
    val minY:Float get() = padding.toFloat()
    val maxX:Float get() = viewWidth.toFloat() + padding
    val maxY:Float get() = viewHeight.toFloat() + padding
//    val rangeX: ClosedFloatingPointRange<Float> get() = minX..maxX
//    val rangeY: ClosedFloatingPointRange<Float> get() = minY..maxY

    var maskSy:Float
        get() = rsy * viewHeight + padding
        private set(v) { rsy = (v-padding) / viewHeight }
    private fun correctSy(v:Float):Float = v.coerceIn(padding.toFloat(), maskEy-minMaskWidth+padding)
    private fun setMaskSy(v:Float):Float {
        return  correctSy(v).also { maskSy = it }
    }
    var maskEx:Float
        get() = rex * viewWidth + padding
        private set(v) { rex = (v-padding) / viewWidth }
    private fun correctEx(v:Float):Float = v.coerceIn(maskSx+minMaskWidth+padding, viewWidth.toFloat()+padding)
    fun setMaskEx(v:Float):Float {
        return correctEx(v).also { maskEx = it }
    }
    var maskEy:Float
        get() = rey * viewHeight + padding
        private set(v) { rey = (v-padding) / viewHeight }
    private fun correctEy(v:Float):Float = v.coerceIn(maskSy+minMaskWidth+padding, viewHeight.toFloat()+padding)
    fun setMaskEy(v:Float):Float {
        return correctEy(v).also { maskEy = it }
    }

    private fun getDeviceScreenSize(aspect: AspectMode):Pair<Float, Float> {
        return UtImmortalTaskManager.application.resources.displayMetrics.run {
            val longSide = max(widthPixels, heightPixels).toFloat()
            val shortSide = min(widthPixels, heightPixels).toFloat()
            if (aspect == AspectMode.ASPECT_SCREEN_LANDSCAPE) {
                longSide to shortSide
            } else {
                shortSide to longSide
            }
        }
    }


    /**
     * AspectModeで指定されたアスペクト比に調整するための、x/y補正量を計算する
     * ただし、常に Landscapeとして計算する。
     * @return Float 補正後の高さ
     */
    private fun correctHeightBasedOnWidth(newWidth:Float, newHeight:Float, aspect:AspectMode):Float {
        if (aspectMode.value == AspectMode.FREE) return newHeight

        val (horizontal,vertical) = when (aspect) {
            AspectMode.ASPECT_SCREEN_LANDSCAPE, AspectMode.ASPECT_SCREEN_PORTRAIT -> getDeviceScreenSize(aspect)
            else -> aspect.horizontal to aspect.vertical
        }

        // 幅を基準に高さを調整する
        val corrHeight = newWidth * vertical / horizontal
        logger.debug("${cropFlows.height} -- ${(maxY-minY)/(maxX-minX)*(cropFlows.width)}")
        return corrHeight

        // 当初、変化量の多い方を基準にサイズ計算するのが妥当と考えたが、
        // 実際に操作してみると、縦・横基準のサイズ計算が頻繁に切り替わって、表示が安定しないので却下
        // このアプリの場合は landscape固定で利用されるので、常に横方向基準とすることで自然な動作になった。
        // もし Portraitもサポートするなら、if (landscape) {} else {} のように書くとよいと思う。

//        if (abs(dx) > abs(dy)) {
//            // x方向の変化量が大きい --> 幅を基準に調整
//            val ch = newWidth * aspect.shortSide / aspect.longSide
//            return 0f to (ch - newHeight)
//        } else {
//            // y方向の変化量が大きい --> 高さを基準に調整
//            val cw = newHeight * aspect.longSide / aspect.shortSide
//            return (cw - newWidth) to 0f
//        }
    }
    private fun correctWidthBasedOnHeight(newWidth:Float, newHeight:Float, aspect:AspectMode):Float {
        if (aspectMode.value == AspectMode.FREE) return newWidth

        val (horizontal,vertical) = when (aspect) {
            AspectMode.ASPECT_SCREEN_LANDSCAPE, AspectMode.ASPECT_SCREEN_PORTRAIT -> getDeviceScreenSize(aspect)
            else -> aspect.horizontal to aspect.vertical
        }
        // 高さを基準に調整
        val corrWidth = newHeight * horizontal / vertical
        return corrWidth
    }

    fun moveLeftTop(x:Float, y:Float) {
        when (aspectMode.value) {
            AspectMode.FREE -> {
                setMaskSx(x)
                setMaskSy(y)
            }
            else -> {
                val nsx = correctSx(x)
                val nsy = correctSy(y)
                val corrHeight = correctHeightBasedOnWidth( maskEx-nsx, maskEy-nsy, aspectMode.value)
                val newSy = maskEy - corrHeight
                if (newSy >= minY) {
                    setMaskSx(x)
                    setMaskSy(newSy)
                } else {
                    val corrWidth = correctWidthBasedOnHeight(maskEx-nsx, maskEy-minY, aspectMode.value)
                    setMaskSx(maskEx-corrWidth)
                    setMaskSy(minY)
                }
            }
        }
    }

    fun moveLeftBottom(x:Float, y:Float) {
        when (aspectMode.value) {
            AspectMode.FREE -> {
                setMaskSx(x)
                setMaskEy(y)
            }
            else -> {
                val nsx = correctSx(x)
                val ney = correctEy(y)
                val corrHeight = correctHeightBasedOnWidth( maskEx-nsx, ney - maskSy, aspectMode.value)
                val newEy = maskSy+corrHeight
                if (newEy <= maxY) {
                    setMaskSx(x)
                    setMaskEy(newEy)
                } else {
                    val corrWidth = correctWidthBasedOnHeight(maskEx - nsx, maxY - maskSy, aspectMode.value)
                    setMaskSx(maskEx - corrWidth)
                    setMaskEy(maxY)
                }
            }
        }
    }
    fun moveRightTop(x:Float, y:Float) {
        when (aspectMode.value) {
            AspectMode.FREE -> {
                setMaskEx(x)
                setMaskSy(y)
            }
            else -> {
                val nex = correctEx(x)
                val nsy = correctSy(y)
                val corrHeight = correctHeightBasedOnWidth(nex-maskSx, maskEy-nsy, aspectMode.value)
                val newSy = maskEy - corrHeight
                if (newSy>=minY) {
                    setMaskEx(x)
                    setMaskSy(newSy)
                } else {
                    val corrWidth = correctWidthBasedOnHeight(nex-maskSx, maskEy-minY, aspectMode.value)
                    setMaskEx(maskSx+corrWidth)
                    setMaskSy(minY)
                }
            }
        }
    }
    fun moveRightBottom(x:Float, y:Float) {
        when (aspectMode.value) {
            AspectMode.FREE -> {
                setMaskEx(x)
                setMaskEy(y)
            }
            else -> {
                val nex = correctEx(x)
                val ney = correctEy(y)

                val corrHeight = correctHeightBasedOnWidth(nex-maskSx, ney-maskSy, aspectMode.value)
                val newEy = maskSy+corrHeight
                if (newEy <= maxY) {
                    setMaskEx(x)
                    setMaskEy(newEy)
                } else {
                    val corrWidth = correctWidthBasedOnHeight(nex-maskSx, maxY-maskSy, aspectMode.value)
                    setMaskEx(maskSx+corrWidth)
                    setMaskEy(maxY)
                }
            }
        }
    }

    // mask の幅と高さ
    private val maskWidth:Float
        get() = maskEx - maskSx
    private val maskHeight:Float
        get() = maskEy - maskSy

    /**
     * maskのサイズを維持したまま、左上の座標を (x,y) に移動する。
     *
     * mask が view の範囲を超える場合は、view 内に収まるように調整する。
     * @param x mask の左上の X座標 (padding 込み)
     * @param y mask の左上の Y座標 (padding 込み)
     */
    fun moveTo(x:Float, y:Float) {
        val w = maskWidth
        val h = maskHeight
        val newSx = x.coerceIn(padding.toFloat(), viewWidth - w + padding)
        val newSy = y.coerceIn(padding.toFloat(), viewHeight - h + padding)
        maskSx = newSx
        maskSy = newSy
        maskEx = newSx + w
        maskEy = newSy + h
    }

    val isCropped = MutableStateFlow(false)

    interface ICropFlows {
        val cropSx: StateFlow<Int>
        val cropSy: StateFlow<Int>
        val cropWidth: StateFlow<Int>
        val cropHeight: StateFlow<Int>
    }
    inner class CropFlows : ICropFlows {
        override val cropSx = MutableStateFlow(0)
        override val cropSy = MutableStateFlow(0)
        override val cropWidth = MutableStateFlow(0)
        override val cropHeight = MutableStateFlow(0)

        var width:Float = 0f
        var height:Float = 0f

        fun setSize(width:Float, height:Float):CropFlows {
            this.width = width
            this.height = height
            update()
            return this
        }

        fun update() {
            val cropped = rsx != 0f || rsy != 0f || rex != 1f || rey != 1f
            if (isCropped.value!=cropped) isCropped.value = cropped
            if (width==0f||height==0f) return
            val sx = (rsx * width).roundToInt()
            val sy = (rsy * height).roundToInt()
            val w = ((rex-rsx) * width).roundToInt()
            val h = ((rey-rsy) * height).roundToInt()
            if(cropSx.value != sx) cropSx.value = sx
            if(cropSy.value != sy) cropSy.value = sy
            if(cropWidth.value != w) cropWidth.value = w
            if(cropHeight.value != h) cropHeight.value = h
        }
        init {
            update()
        }
    }
    val cropFlows :CropFlows = CropFlows()
    fun updateCropFlow(size: Size?) {
        if (size!=null) {
            cropFlows.setSize(size.width.toFloat(), size.height.toFloat())
        } else {
            cropFlows.setSize(0f, 0f)
        }
    }
//    fun startCropFlow(width:Int, height:Int):ICropFlows {
//        return cropFlows.setSize(width.toFloat(), height.toFloat())
//    }
//    fun stopCropFlow():ICropFlows {
//        return cropFlows.setSize(0f, 0f)
//    }

    data class CropRect(val sx:Int, val sy:Int, val width:Int, val height:Int) {
        val asRect: Rect get() = Rect(sx, sy, sx+width, sy+height)
    }
    fun cropRect(width:Int, height:Int):CropRect {
        return CropRect(
            (rsx * width.toFloat()).roundToInt(),
            (rsy * height.toFloat()).roundToInt(),
            ((rex-rsx) * width.toFloat()).roundToInt(),
            ((rey-rsy) * height.toFloat()).roundToInt(),
        )
    }
    fun cropRect(bitmap:Bitmap):CropRect {
        return cropRect(bitmap.width, bitmap.height)
    }

    /**
     * ソース画像から mask情報に従って crop 領域を切り出す
     *
     * @param bitmap ソース画像
     * @return 切り出した画像 (cropWidth x cropHeight)
     */
    fun cropBitmap(bitmap:Bitmap):Bitmap {
        val crop = cropRect(bitmap)
        if (crop.sx==0 && crop.sy == 0 && crop.width == bitmap.width && crop.height == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, crop.sx, crop.sy, crop.width, crop.height)
    }

    fun getParams():MaskCoreParams {
        previousAspectMode = aspectMode.value
        return MaskCoreParams(rsx, rsy, rex, rey)
    }

    fun setParams(p:MaskCoreParams) {
        rsx = p.rsx
        rsy = p.rsy
        rex = p.rex
        rey = p.rey
        isDirty = true
    }
}

