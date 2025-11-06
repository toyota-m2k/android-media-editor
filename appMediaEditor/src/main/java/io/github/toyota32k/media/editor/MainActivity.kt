package io.github.toyota32k.media.editor

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.observe
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.dialog.broker.IUtActivityBrokerStoreProvider
import io.github.toyota32k.dialog.broker.UtActivityBrokerStore
import io.github.toyota32k.dialog.broker.UtMultiPermissionsBroker
import io.github.toyota32k.dialog.broker.pickers.UtCreateFilePicker
import io.github.toyota32k.dialog.broker.pickers.UtMediaFilePicker
import io.github.toyota32k.dialog.broker.pickers.UtOpenFilePicker
import io.github.toyota32k.dialog.mortal.UtMortalActivity
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.lib.media.editor.model.AbstractSplitHandler
import io.github.toyota32k.lib.media.editor.model.IMediaSourceWithMutableChapterList
import io.github.toyota32k.lib.media.editor.model.MaskCoreParams
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.lib.media.editor.output.DefaultAudioStrategySelector
import io.github.toyota32k.lib.media.editor.output.GenericSaveFileHandler
import io.github.toyota32k.lib.media.editor.output.InteractiveOutputFileProvider
import io.github.toyota32k.lib.media.editor.output.InteractiveVideoStrategySelector
import io.github.toyota32k.lib.media.editor.output.OverwriteFileProvider
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.lib.player.model.chapter.MutableChapterList
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.logger.UtLogConfig
import io.github.toyota32k.media.editor.databinding.ActivityMainBinding
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.toAndroidFile
import io.github.toyota32k.utils.android.CompatBackKeyDispatcher
import io.github.toyota32k.utils.android.setLayoutWidth
import io.github.toyota32k.utils.gesture.Direction
import io.github.toyota32k.utils.gesture.UtScaleGestureManager
import io.github.toyota32k.utils.toggle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class MainActivity : UtMortalActivity(), IUtActivityBrokerStoreProvider {
    override val logger = UtLog("Main")
    override val activityBrokers = UtActivityBrokerStore(this, UtOpenFilePicker(), UtCreateFilePicker(), UtMultiPermissionsBroker(), UtMediaFilePicker())
    private val binder = Binder()
    private lateinit var controls: ActivityMainBinding
    private val compatBackKeyDispatcher = CompatBackKeyDispatcher()

    class MediaSource private constructor(val file:AndroidFile, override val type:String) : IMediaSourceWithMutableChapterList {
        override val id: String
            get() = file.safeUri.toString()
        override val name: String
            get() = file.getFileName() ?: "unnamed"
        override val uri: String
            get() = file.safeUri.toString()
        override var startPosition = AtomicLong()
        override val trimming: Range = Range.empty

        val chapterList = MutableChapterList(emptyList())
        override suspend fun getChapterList(): IMutableChapterList {
            return chapterList
        }

        companion object {
            fun fromFile(file:AndroidFile):MediaSource? {
                val type = when (file.getContentType()) {
                    "video/mp4"-> "mp4"
                    "image/png"-> "png"
                    "image/jpeg"-> "jpg"
                    else-> return null
                }
                return MediaSource(file, type)
            }
        }
    }

    class MainViewModel(application: Application): AndroidViewModel(application) {
        class SplitHandler : AbstractSplitHandler(true) {
            override suspend fun splitVideoAt(targetSource: IMediaSource, positionMs: Long): Boolean {
                TODO("Not yet implemented")
            }

        }

        val localData = LocalData(application)


        val editorModel = MediaEditorModel.Builder(
            PlayerControllerModel.Builder(application, viewModelScope)
                .supportChapter(false)
                .supportSnapshot(::snapshot)
                .enableRotateLeft()
                .enableRotateRight()
                .enableSeekSmall(0,0)
                .enableSeekMedium(1000, 3000)
                .enableSeekLarge(5000, 10000)
                .enableSliderLock(true)
                .enablePhotoViewer()
                .build()
            )
            .supportChapterEditor()
            .supportCrop()
            .supportSplit(SplitHandler())
            .setSaveFileHandler { controllerModel ->
                GenericSaveFileHandler.create(true, application,
                    controllerModel,
                    InteractiveVideoStrategySelector(),
                    DefaultAudioStrategySelector)
            }
            .build()

        fun snapshot(pos:Long, bmp: Bitmap) {

        }

        override fun onCleared() {
            super.onCleared()
            editorModel.close()
        }

        val targetMediaSource = MutableStateFlow<MediaSource?>(null)
        val isEditing = targetMediaSource.map { it!=null }
        val requestShowPanel = MutableStateFlow(true)

        // view state
        // - buttonPane全面表示
        // - buttonPane控えめ表示
        // - buttonPane非表示
        enum class ViewState {
            FULL,
            HALF,
            NONE,
        }
        fun viewState(targetMediaSource: MediaSource?, requestShowPanel:Boolean) : ViewState =  when {
            targetMediaSource == null -> ViewState.FULL
            requestShowPanel -> ViewState.HALF
            else -> ViewState.NONE
        }

        val viewState = combine(isEditing, requestShowPanel) { isEditing, requestShowPanel ->
            when {
                !isEditing-> ViewState.FULL
                requestShowPanel-> ViewState.HALF
                else-> ViewState.NONE
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ViewState.FULL)


        fun openMediaFile(file: Uri, serializedChapters:String? = null, serializedCropParams:String? = null):MediaSource? {
            val orgSource = targetMediaSource.value
            if (orgSource?.file?.uri == file) return orgSource
            val source = MediaSource.fromFile(file.toAndroidFile(getApplication()))?.apply {
                if (!isPhoto) {
                    chapterList.deserialize(serializedChapters)
                }
            } ?: return null
            localData.editingUri = file
            targetMediaSource.value = source
            editorModel.playerModel.setSource(source)
            requestShowPanel.value = false
            if (serializedCropParams!=null) {
                editorModel.cropHandler.maskViewModel.setParams(MaskCoreParams.fromJson(serializedCropParams))
            }
            return source
        }

        fun storeToLocalData() {
            localData.apply {
                val source = targetMediaSource.value
                serializedChapters = source?.chapterList?.serialize()
                serializedCropParams = editorModel.cropHandler.maskViewModel.getParams().serialize()
                if (source!=null && !source.isPhoto) {
                    playPosition = editorModel.playerModel.currentPosition
                    isPlaying = editorModel.playerModel.isPlaying.value
                } else {
                    playPosition = 0
                    isPlaying = false
                }
            }
        }

        fun restoreFromLocalData() {
            localData.editingUri?.also { file ->
                val source = openMediaFile(file, localData.serializedChapters, localData.serializedCropParams) ?: return@also
                if (source.isPhoto) return
                editorModel.playerModel.seekTo(localData.playPosition)
                if (localData.isPlaying) {
                    editorModel.playerControllerModel.commandPlay.invoke()
                }
            }
        }

    }

    private val viewModel by viewModels<MainViewModel>()
    private lateinit var gestureManager: UtScaleGestureManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UtLogConfig.logLevel = Log.VERBOSE

        enableEdgeToEdge()
        controls = ActivityMainBinding.inflate(layoutInflater)
        setContentView(controls.root)

        setupWindowInsetsListener(controls.root)

        compatBackKeyDispatcher.register(this) {
            if (viewModel.targetMediaSource.value != null) {
                viewModel.targetMediaSource.value = null
            } else {
                finish()
            }
        }

        // Gesture / Scaling
        gestureManager = UtScaleGestureManager(this.applicationContext, enableDoubleTap = true, controls.editorPlayerView.manipulationTarget, minScale = 1f)
            .setup(this) {
                onTap {
                    viewModel.editorModel.playerModel.togglePlay()
                }
                onDoubleTap {
                    gestureManager.agent.resetScrollAndScale()
                }
                onFlickHorizontal { event->
                    when(event.direction) {
                        Direction.Start -> viewModel.requestShowPanel.value = false
                        Direction.End -> viewModel.requestShowPanel.value = true
                    }
                }
            }

        val AnimDuration = 200L
        binder
            .owner(this)
            .observe(viewModel.viewState) { vs ->
                when (vs) {
                    MainViewModel.ViewState.FULL -> {
                        controls.buttonPane.setLayoutWidth(ViewGroup.LayoutParams.MATCH_PARENT)
//                        controls.buttonPane.x = controls.root.paddingStart.toFloat()
//                        logger.debug("INSET(STATE): left=${controls.root.paddingStart}px (${px2dp(controls.root.paddingStart)} dp)")
                        controls.buttonPane.animate()
                            .x(controls.root.paddingStart.toFloat())
                            .setDuration(AnimDuration)
                            .withEndAction {
                                // padding+アニメーションの気持ちの悪い動作に対応
                                // setupWindowInsetsListener() で、CUTOUTなどを避けるためにpaddingStartが設定されることがあるが、
                                // paddingが設定されるタイミングで子ビューのx座標が更新される（paddingの値が加算される）。
                                // （子ビューの座標系は padding を含んだ値を取るので、paddingが変化すると、それに合わせて座標値が変化する。）
                                // そのため、子ビューの原点は (0,0) ではなく、(paddingStart, paddingTop) となる。
                                // ところが、アニメーションによって子ビューの座標を変更していると、アニメーション中に paddingが変化してしまうことがある。
                                // これによる位置ずれを回避するため、アニメーション終了時に、座標を再設定しておく。
                                // 謎の隙間が生じる現象があって、原因特定に結構苦労したのでメモしておく。
                                controls.buttonPane.x = controls.root.paddingStart.toFloat()
                            }
                            .start()
                    }

                    MainViewModel.ViewState.HALF -> {
                        controls.buttonPane.setLayoutWidth(ViewGroup.LayoutParams.WRAP_CONTENT)
                        controls.buttonPane.animate()
                            .x(controls.root.paddingStart.toFloat())
                            .setDuration(AnimDuration)
                            .withEndAction {
                                controls.buttonPane.x = controls.root.paddingStart.toFloat()
                            }
                            .start()
                    }

                    MainViewModel.ViewState.NONE -> {
                        controls.buttonPane.animate()
                            .x(-controls.buttonPane.width.toFloat())
                            .setDuration(AnimDuration)
                            .start()
                    }
                }
            }
//            .add(addRootViewInsetsListener(this){
//                if (viewModel.viewState.value!= MainViewModel.ViewState.NONE) {
////                    logger.debug("INSET: left=${it.left}px (${px2dp(it.left)} dp)")
////                    controls.buttonPane.x = it.left.toFloat()
//                }
//            })
            .visibilityBinding(controls.menuButton, combine(viewModel.isEditing, viewModel.editorModel.cropHandler.croppingNow) { isEditing, cropping ->
                isEditing && !cropping
            })
            .clickBinding(controls.menuButton) {
                viewModel.requestShowPanel.toggle()
            }
            .clickBinding(controls.buttonOpen) {
                openMediaFile()
            }
            .clickBinding(controls.buttonSave) {
                lifecycleScope.launch {
                    viewModel.editorModel.playerControllerModel.commandPause.invoke()
                    viewModel.storeToLocalData()
                    viewModel.editorModel.saveFile(InteractiveOutputFileProvider("", null))
                }
            }
            .clickBinding(controls.buttonClose) {
                viewModel.localData.editingUri = null
                viewModel.targetMediaSource.value = null
            }
            .observe(viewModel.editorModel.cropHandler.croppingNow) {
                if (it) {
                    gestureManager.agent.resetScrollAndScale()
                }
                enableGestureManager(!it)
            }
        controls.editorPlayerView.bindViewModel(viewModel.editorModel, binder)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  // スリープしない
        )
        viewModel.restoreFromLocalData()

        UtImmortalTaskManager.immortalTaskScope.launch {
            activityBrokers.multiPermissionBroker.Request()
                .addIf(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .execute()
        }
    }

    private fun enableGestureManager(sw:Boolean) {
        val view = gestureManager.manipulationTarget.parentView
        if (sw) {
            gestureManager.gestureInterpreter.attachView(view)
        } else {
            gestureManager.gestureInterpreter.detachView(view)
        }
    }

    private fun openMediaFile() {
        UtImmortalTask.launchTask {
            val file = activityBrokers.openFilePicker.selectFile(arrayOf("video/*", "image/*"))
            if (file != null) {
                viewModel.openMediaFile(file)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.storeToLocalData()
    }
}