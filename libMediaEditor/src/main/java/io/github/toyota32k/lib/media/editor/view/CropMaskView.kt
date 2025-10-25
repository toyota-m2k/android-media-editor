package io.github.toyota32k.lib.media.editor.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.observe
import io.github.toyota32k.lib.media.editor.R
import io.github.toyota32k.lib.media.editor.model.AspectMode
import io.github.toyota32k.lib.media.editor.model.CropMaskViewModel
import io.github.toyota32k.utils.android.StyledAttrRetriever
import io.github.toyota32k.utils.android.dp
import io.github.toyota32k.utils.android.dp2px
import io.github.toyota32k.utils.android.getLayoutHeight
import io.github.toyota32k.utils.android.getLayoutWidth

class CropMaskView@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : View(context, attrs, defStyleAttr) {
    private lateinit var maskDrawable: Drawable // = 0x50FFFFFF.toDrawable()
    private lateinit var linePaint:Paint
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private lateinit var handleFillPaint: Paint
    private lateinit var handleStrokePaint:Paint
    private var showHandle:Boolean = false

    fun showHandle(sw:Boolean) {
        if (showHandle!=sw) {
            showHandle = sw
            this.isClickable = sw
            invalidate()
        }
    }

    private fun setViewAttributes(sar: StyledAttrRetriever) {
        maskDrawable = sar.getDrawableWithAlphaOnFallback(R.styleable.MediaEditor_ameCropMaskColor, com.google.android.material.R.attr.colorOnSurface, Color.WHITE, 0xB0)
        showHandle = sar.sa.getBoolean(R.styleable.MediaEditor_ameShowHandle, true)

        this.isClickable = showHandle

        linePaint =  Paint().apply {
            style = Paint.Style.STROKE
            color = sar.getColor(R.styleable.MediaEditor_ameCropMaskColor, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
            strokeWidth = context.dp2px(1f)
        }
        handleFillPaint = Paint().apply {
            style = Paint.Style.FILL
            color = sar.getColor(R.styleable.MediaEditor_ameHandleFillColor, androidx.appcompat.R.attr.colorPrimary, Color.YELLOW)
        }
        handleStrokePaint = Paint().apply {
            style = Paint.Style.STROKE
            color = sar.getColor(R.styleable.MediaEditor_ameHandleStrokeColor, com.google.android.material.R.attr.colorPrimaryContainer, Color.BLUE)
            strokeWidth = sar.getDimensionPixelSize(R.styleable.MediaEditor_ameHandleStrokeWidth, 4.dp).toFloat()
            isAntiAlias = true
        }
    }

    fun setMaskDrawable(drawable: Drawable) {
        maskDrawable = drawable
        invalidate()
    }

    fun setCropMaskViewAttributes(sarForEditor:StyledAttrRetriever) {
        setViewAttributes(sarForEditor)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        viewModel?.setViewDimension(getLayoutWidth(), getLayoutHeight(), left)
        super.setPadding(left, top, right, bottom)
    }

    init {
        StyledAttrRetriever(context, attrs, R.styleable.MediaEditor, defStyleAttr,0).use { sarForEditor ->
            setCropMaskViewAttributes(sarForEditor)
        }
        this.background = Color.TRANSPARENT.toDrawable()
    }

    var viewModel: CropMaskViewModel? = null

    fun bindViewModel(binder: Binder, vm: CropMaskViewModel) {
        viewModel = vm
        vm.setViewDimension(getLayoutWidth(), getLayoutHeight(), paddingStart)
        vm.clearDirty { invalidate() }
        binder.observe(vm.aspectMode) {
                if (it!= AspectMode.FREE&&vm.viewSizeAvailable) {
                    vm.moveRightBottom(vm.maskEx, vm.maskEy)
                    vm.clearDirty { invalidate() }
                }
            }
    }

//    fun resetCrop() {
//        viewModel?.apply {
//            resetCrop()
//            clearDirty { invalidate() }
//        }
//    }
//
//    fun applyCropFromMemory() {
//        viewModel?.apply {
//            memory.value?.also {
//                setParams(it)
//                clearDirty { invalidate() }
//            }
//        }
//    }

    fun invalidateIfNeed() {
        viewModel?.apply {
            clearDirty { invalidate() }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        viewModel?.let { vm->
            if (vm.rawViewWidth!=w || vm.rawViewHeight!=h) {
                vm.setViewDimension(w, h, paddingStart)
                vm.clearDirty { invalidate() }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val vm = viewModel ?: return
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        maskDrawable.setBounds(vm.padding, vm.padding, width-vm.padding, height-vm.padding)
        maskDrawable.draw(canvas)

        canvas.drawRect(vm.maskSx, vm.maskSy, vm.maskEx, vm.maskEy, clearPaint)
        canvas.restoreToCount(saveCount)

        // mask の枠を描く
        canvas.drawRect(vm.maskSx, vm.maskSy, vm.maskEx, vm.maskEy, linePaint)

        if (vm.padding>0 && showHandle) {

            // mask の四隅にハンドルを描く
            val handleSize = vm.padding * 2f
            // 左上
            canvas.drawCircle(vm.maskSx, vm.maskSy, handleSize / 2, handleFillPaint)
            canvas.drawCircle(vm.maskSx, vm.maskSy, (handleSize - handleStrokePaint.strokeWidth) / 2, handleStrokePaint)
            // 右上
            canvas.drawCircle(vm.maskEx, vm.maskSy, handleSize / 2, handleFillPaint)
            canvas.drawCircle(vm.maskEx, vm.maskSy, (handleSize - handleStrokePaint.strokeWidth) / 2, handleStrokePaint)
            // 左下
            canvas.drawCircle(vm.maskSx, vm.maskEy, handleSize / 2, handleFillPaint)
            canvas.drawCircle(vm.maskSx, vm.maskEy, (handleSize - handleStrokePaint.strokeWidth) / 2, handleStrokePaint)
            // 右下
            canvas.drawCircle(vm.maskEx, vm.maskEy, handleSize / 2, handleFillPaint)
            canvas.drawCircle(vm.maskEx, vm.maskEy, (handleSize - handleStrokePaint.strokeWidth) / 2, handleStrokePaint)
        }
    }

    enum class HitResult {
        None,
        LeftTop,
        RightTop,
        LeftBottom,
        RightBottom,
        Move
    }

    inner class DragState {
        var hit: HitResult = HitResult.None
        private fun hitTest(x:Float, y:Float): HitResult {
            val vm = viewModel ?: return HitResult.None
            val handleSize = (vm.padding*2f).coerceAtLeast(context.dp2px(36f))
            return when {
                // 左上
                (x in (vm.maskSx-handleSize)..(vm.maskSx+handleSize) && y in (vm.maskSy-handleSize)..(vm.maskSy+handleSize)) -> HitResult.LeftTop
                // 右上
                (x in (vm.maskEx-handleSize)..(vm.maskEx+handleSize) && y in (vm.maskSy-handleSize)..(vm.maskSy+handleSize)) -> HitResult.RightTop
                // 左下
                (x in (vm.maskSx-handleSize)..(vm.maskSx+handleSize) && y in (vm.maskEy-handleSize)..(vm.maskEy+handleSize)) -> HitResult.LeftBottom
                // 右下
                (x in (vm.maskEx-handleSize)..(vm.maskEx+handleSize) && y in (vm.maskEy-handleSize)..(vm.maskEy+handleSize)) -> HitResult.RightBottom
                // mask 内部
                (x in vm.maskSx..vm.maskEx && y in vm.maskSy..vm.maskEy) -> HitResult.Move
                else -> HitResult.None
            }
        }

        fun reset() {
            hit = HitResult.None
        }
        var x:Float = 0f
        var y:Float = 0f
        var sx:Float = 0f
        var sy:Float = 0f

//        val isDragging get() = hit != HitResult.None

        fun start(x:Float, y:Float): Boolean {
            val vm = viewModel ?: return false
            val hit = hitTest(x, y)
            if (hit == HitResult.None) return false
            this.hit = hit
            this.x = x
            this.y = y
            this.sx = vm.maskSx
            this.sy = vm.maskSy
            return true
        }

        fun move(x:Float, y:Float) {
            val vm = viewModel ?: return
            val dx = x - this.x
            val dy = y - this.y
            vm.moveTo(sx + dx, sy + dy)
        }
    }
    private val dragState = DragState()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val vm = viewModel
        if (vm==null || !showHandle) {
            return super.onTouchEvent(event)
        }
        when(event.action) {
            MotionEvent.ACTION_DOWN -> {
                // このビューがタッチイベントを受け取ることを宣言する
                return dragState.start(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                when(dragState.hit) {
                    HitResult.LeftTop -> {
                        vm.moveLeftTop(event.x, event.y)
                        vm.clearDirty { invalidate() }
                    }
                    HitResult.RightTop -> {
                        vm.moveRightTop(event.x, event.y)
                        vm.clearDirty { invalidate() }
                    }
                    HitResult.LeftBottom -> {
                        vm.moveLeftBottom(event.x, event.y)
                        vm.clearDirty { invalidate() }
                    }
                    HitResult.RightBottom -> {
                        vm.moveRightBottom(event.x, event.y)
                        vm.clearDirty { invalidate() }
                    }
                    HitResult.Move -> {
                        // マスクの中心をタッチ位置に移動する
                        dragState.move(event.x, event.y)
                        vm.clearDirty { invalidate() }
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // タッチ終了
                dragState.reset()
                return true
            }
            else -> {}
        }

        return super.onTouchEvent(event)
    }
}