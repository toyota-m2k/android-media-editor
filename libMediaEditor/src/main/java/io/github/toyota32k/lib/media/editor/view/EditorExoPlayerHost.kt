package io.github.toyota32k.lib.media.editor.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.BoolConvert
import io.github.toyota32k.binder.VisibilityBinding
import io.github.toyota32k.binder.combinatorialVisibilityBinding
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.observe
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.dialog.UtDialogBase
import io.github.toyota32k.lib.media.editor.databinding.EditorExoPlayerHostBinding
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.EditorPlayerViewAttributes
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.utils.FlowableEvent
import io.github.toyota32k.utils.android.FitMode
import io.github.toyota32k.utils.android.StyledAttrRetriever
import io.github.toyota32k.utils.android.UtFitter
import io.github.toyota32k.utils.android.dp
import io.github.toyota32k.utils.android.dp2px
import io.github.toyota32k.utils.android.getLayoutHeight
import io.github.toyota32k.utils.android.getLayoutWidth
import io.github.toyota32k.utils.android.lifecycleOwner
import io.github.toyota32k.utils.android.px2dp
import io.github.toyota32k.utils.android.setLayoutSize
import io.github.toyota32k.utils.gesture.IUtManipulationTarget
import io.github.toyota32k.utils.gesture.UtMinimumManipulationTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

class EditorExoPlayerHost  @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : FrameLayout(context, attrs, defStyleAttr), PlayerControllerModel.IScreenshotSource {
    val logger = AmeGlobal.logger

    // 使う人（ActivityやFragment）がセットすること
    private lateinit var model: MediaEditorModel

    // Controls
    val controls = EditorExoPlayerHostBinding.inflate(LayoutInflater.from(context), this, true)
    private val playerContainer get() = controls.expPlayerContainer
    private val exoPlayer get() = controls.expPlayerView
    private val photoView get() = controls.expPhotoView
    private val photoAltView get() = controls.expPhotoAltView
    private val maskView get() = controls.expCropMaskView

    enum class ProgressRingSize(val value:Int) {
        Small(1),
        Medium(2),
        Large(3),
        None(4),
        ;
        companion object {
            fun fromValue(value:Int): ProgressRingSize = entries.firstOrNull { it.value==value } ?: Medium
        }
    }
    private var progressRingGravity: Int = 0
    private var progressRingSize:ProgressRingSize = ProgressRingSize.Medium
    private val progressRing:ProgressBar?
        get() = when (progressRingSize) {
            ProgressRingSize.Small -> controls.expProgressRingSmall
            ProgressRingSize.Medium -> controls.expProgressRingMedium
            ProgressRingSize.Large -> controls.expProgressRingLarge
            ProgressRingSize.None -> null
        }

    var useExoController:Boolean
        get() = exoPlayer.useController
        set(v) { exoPlayer.useController = v }

    private val rootViewSize = MutableStateFlow<Size?>(null)

    fun setPlayerAttributes(epa: EditorPlayerViewAttributes) {
        val sar = epa.sarForPlayer
        if (sar.sa.getBoolean(io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampAttrsByParent, true)) {
            controls.expPlayerRoot.background = sar.getDrawable(
                io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampPlayerBackground,
                com.google.android.material.R.attr.colorSurface,
                Color.BLACK
            )
        }
        if (sar.sa.getBoolean(io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampPlayerCenteringVertically, false)) {
            val params = controls.expPlayerView.layoutParams as FrameLayout.LayoutParams
            params.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            controls.expPlayerContainer.layoutParams = params
        }

        val ringGravity = sar.sa.getInt(io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampPlayerProgressRingGravity, 0)
        if (ringGravity!=0) {
            progressRingGravity = ringGravity
        }
        val ringSize = sar.sa.getInt(io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampPlayerProgressRingSize, 0)
        if (ringSize!=0) {
            progressRingSize = ProgressRingSize.fromValue(ringSize)
        }
        if (sar.sa.getBoolean(io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampAttrsByParent, true)) {
            controls.expCropMaskView.setCropMaskViewAttributes(epa.sarForEditor)
        }
    }

    init {
        EditorPlayerViewAttributes(context, attrs, defStyleAttr).use { epa->
            setPlayerAttributes(epa)
        }
    }

    fun associatePlayer() {
        model.playerModel.associatePlayerView(exoPlayer)
    }
    fun dissociatePlayer() {
        model.playerModel.dissociatePlayerView(exoPlayer)
    }

    fun bindViewModel(mediaEditorModel: MediaEditorModel, binder: Binder) {
        val owner = lifecycleOwner()!!
        val scope = owner.lifecycleScope

        this.model = mediaEditorModel
        if(model.playerControllerModel.autoAssociatePlayer) {
            associatePlayer()
        }
        model.playerControllerModel.exoPlayerSnapshotSource = this

        val activeProgressRing = progressRing
        if (progressRingGravity!=0 && activeProgressRing!=null) {
            val params = activeProgressRing.layoutParams as FrameLayout.LayoutParams
            params.gravity = progressRingGravity
            activeProgressRing.layoutParams = params
        }
        maskView.bindViewModel(binder, model.cropHandler.maskViewModel)
        binder
            .conditional(activeProgressRing!=null) {
                visibilityBinding(activeProgressRing!!, model.playerModel.isLoading, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByInvisible)
            }
            .visibilityBinding(controls.expErrorMessage, model.playerModel.isError, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByInvisible)
            .visibilityBinding(controls.serviceArea, combine(model.playerModel.isLoading,model.playerModel.isError) { l, e-> l||e}, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByInvisible)
            .textBinding(controls.expErrorMessage, model.playerModel.errorMessage.filterNotNull())
            .combinatorialVisibilityBinding(model.playerModel.currentSource.map { model.playerModel.isPhotoViewerEnabled && it?.isPhoto == true }) {
                straightInvisible(photoView)
                inverseInvisible(exoPlayer)
            }
            .conditional( model.playerModel.isPhotoViewerEnabled ) {
                model.playerModel.attachPhotoView(photoView)
                visibilityBinding(photoAltView, combine(model.cropHandler.cropImageModel.isResolutionChanged, model.playerModel.isCurrentSourcePhoto) {r,p-> r && p })
                observe(model.cropHandler.cropImageModel.bitmapScaler.bitmap) { bmp-> photoAltView.setImageBitmap(bmp) }
            }
            .observe(model.cropHandler.croppingNow) { cropping->
                maskView.showHandle(cropping)
                if (cropping) {
                    val padding = context.dp2px(16)
                    maskView.setPadding(padding)
                    exoPlayer.setPadding(padding)
                    photoView.setPadding(padding)
                    photoAltView.setPadding(padding)
                } else {
                    maskView.setPadding(0)
                    exoPlayer.setPadding(0)
                    photoView.setPadding(0)
                    photoAltView.setPadding(0)
                }
            }
            .bindCommand(model.cropHandler.commandResetCrop) { controls.expCropMaskView.invalidateIfNeed() }
            .bindCommand(model.cropHandler.commandRestoreCropFromMemory) { controls.expCropMaskView.invalidateIfNeed() }
        combine(model.cropHandler.croppingNow,model.playerModel.videoSize, model.playerModel.rotation, rootViewSize,  this::updateLayout).launchIn(scope)
    }

    private var handleRadius = 15.dp
    private val mFitter = UtFitter(FitMode.Inside)
    private fun updateLayout(cropping:Boolean, videoSize:Size?, rotation:Int, rootViewSize:Size?) {
        if(rootViewSize==null||videoSize==null) return
        logger.debug("layoutSize = ${videoSize.width} x ${videoSize.height}")

        handler?.post {
            val padding = if (cropping) handleRadius.px(context) * 2 else 0
            if (abs(rotation %180)==0) {
                // image/image_preview/cropOverlay には同じ padding が設定されている
                // コンテナー領域から、そのpaddingを差し引いた領域内に、bitmapを最大表示したときのサイズを計算
                val w = rootViewSize.width - padding
                val h = rootViewSize.height - padding
                mFitter.setLayoutSize(w,h)
                    .fit(videoSize.width, videoSize.height)
                // bitmapのサイズに padding を加えたサイズを imageContainerにセットする。
                playerContainer.setLayoutSize(mFitter.resultWidth.toInt()+padding, mFitter.resultHeight.toInt()+padding)
                playerContainer.translationY = 0f
            } else {
                // image/image_preview/cropOverlay には同じ padding が設定されている
                // コンテナー領域から、そのpaddingを差し引いた領域内に、bitmapを最大表示したときのサイズを計算
                val w = rootViewSize.width - padding
                val h = rootViewSize.height - padding
                mFitter.setLayoutSize(w,h)
                    .fit(videoSize.height, videoSize.width)
                // bitmapのサイズに padding を加えたサイズを imageContainerにセットする。
                playerContainer.setLayoutSize(mFitter.resultHeight.toInt()+padding, mFitter.resultWidth.toInt()+padding)
                playerContainer.translationY = -(mFitter.resultWidth - mFitter.resultHeight) / 2f
            }
            playerContainer.rotation = rotation.toFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if(w>0 && h>0) {
            logger.debug("width=$w (${context.px2dp(w)}dp), height=$h (${context.px2dp(h)}dp)")
            rootViewSize.value = Size(w,h)
        }
    }

    /**
     * VideoPlayerView にズーム機能を付加するための最小限のIUtManipulationTarget実装
     */
    val manipulationTarget: IUtManipulationTarget
        get() = UtMinimumManipulationTarget(controls.root, controls.expPlayerContainer)


    fun takeScreenshotWithPixelCopy(surfaceView: SurfaceView, callback: (Bitmap?) -> Unit) {
        val bitmap: Bitmap = createBitmap(surfaceView.width, surfaceView.height)
        try {
            val handlerThread = HandlerThread("PixelCopier")
            handlerThread.start()
            PixelCopy.request(
                surfaceView, bitmap,
                { copyResult:Int ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        callback(bitmap)
                    }
                    handlerThread.quitSafely()
                },
                Handler(handlerThread.looper)
            )
        } catch (e: IllegalArgumentException) {
            callback(null)
            e.printStackTrace()
        }
    }

    private suspend fun takeScreenshotWithSurfaceView(surfaceView: SurfaceView): Bitmap? {
        logger.debug("capture from SurfaceView")
        return suspendCoroutine { cont ->
            takeScreenshotWithPixelCopy(surfaceView) { bmp->
                cont.resume(bmp)
            }
        }
    }

    private suspend fun takeScreenshotWithTextureView(textureView: TextureView): Bitmap? {
        logger.debug("capture from TextureView")
        return withContext(Dispatchers.IO) {
            textureView.bitmap
        }
    }

    override suspend fun takeScreenshot(): Bitmap? {
        val event = FlowableEvent()
        var listener: OnLayoutChangeListener? = null
        val videoSize = model.playerModel.videoSize.value
        if (videoSize!=null) {
            listener = OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                if (right - left == videoSize.width && bottom - top == videoSize.height) {
                    event.set()
                }
            }
            exoPlayer.addOnLayoutChangeListener(listener)
            val scaleX = exoPlayer.getLayoutWidth() / videoSize.width.toFloat()
            val scaleY = exoPlayer.getLayoutHeight() / videoSize.height.toFloat()
            exoPlayer.setLayoutSize(videoSize.width, videoSize.height)
            exoPlayer.scaleX = scaleX
            exoPlayer.scaleY = scaleY
        }
        event.waitOne(1000L)
        @OptIn(UnstableApi::class)
        val surfaceView = exoPlayer.videoSurfaceView
        if (surfaceView !is SurfaceView && surfaceView !is android.view.TextureView) {
            logger.error("Unknown surface view type: ${surfaceView?.javaClass?.name}")
            return null
        }
        return try {
            if (surfaceView is SurfaceView) {
                takeScreenshotWithSurfaceView(surfaceView)
            } else {
                takeScreenshotWithTextureView(surfaceView as TextureView)
            }
        } finally {
            if (listener != null) {
                exoPlayer.removeOnLayoutChangeListener(listener)
                exoPlayer.setLayoutSize(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                exoPlayer.scaleX = 1f
                exoPlayer.scaleY = 1f
            }
        }
    }
}