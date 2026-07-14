package io.github.toyota32k.media.editor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.BoolConvert
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.observe
import io.github.toyota32k.binder.onViewSizeChanged
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.dialog.broker.IUtActivityBrokerStoreProvider
import io.github.toyota32k.dialog.broker.UtActivityBrokerStore
import io.github.toyota32k.dialog.broker.UtMultiPermissionsBroker
import io.github.toyota32k.dialog.broker.pickers.UtCreateFilePicker
import io.github.toyota32k.dialog.broker.pickers.UtDirectoryPicker
import io.github.toyota32k.dialog.broker.pickers.UtMediaFilePicker
import io.github.toyota32k.dialog.broker.pickers.UtOpenFilePicker
import io.github.toyota32k.dialog.mortal.UtMortalActivity
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.dialog.task.showConfirmMessageBox
import io.github.toyota32k.dialog.task.showYesNoMessageBox
import io.github.toyota32k.lib.media.editor.handler.save.GenericSaveFileHandler
import io.github.toyota32k.lib.media.editor.handler.save.VideoSaveResult
import io.github.toyota32k.lib.media.editor.handler.split.GenericSplitHandler
import io.github.toyota32k.lib.media.editor.model.IMediaSourceWithMutableChapterList
import io.github.toyota32k.lib.media.editor.model.MaskCoreParams
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.lib.player.common.formatSize
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.PhotoSizeOption
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.lib.player.model.StandardPhotoLoader
import io.github.toyota32k.lib.player.model.chapter.MutableChapterList
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.logger.UtLogConfig
import io.github.toyota32k.media.editor.databinding.ActivityMainBinding
import io.github.toyota32k.media.editor.dialog.DetailMessageDialog
import io.github.toyota32k.media.editor.dialog.SnapshotDialog
import io.github.toyota32k.media.editor.project.Project
import io.github.toyota32k.media.editor.project.ProjectDB
import io.github.toyota32k.media.editor.providers.CustomExportToDirectoryFileSelector
import io.github.toyota32k.media.editor.providers.CustomInteractiveOutputFileProvider
import io.github.toyota32k.media.editor.view.ProjectListView
import io.github.toyota32k.media.lib.io.AndroidFile
import io.github.toyota32k.media.lib.io.toAndroidFile
import io.github.toyota32k.utils.TimeSpan
import io.github.toyota32k.utils.UtLib
import io.github.toyota32k.utils.android.CompatBackKeyDispatcher
import io.github.toyota32k.utils.android.PackageUtil
import io.github.toyota32k.utils.android.RefBitmap
import io.github.toyota32k.utils.android.dp
import io.github.toyota32k.utils.android.setLayoutWidth
import io.github.toyota32k.utils.gesture.Direction
import io.github.toyota32k.utils.gesture.UtScaleGestureManager
import io.github.toyota32k.utils.toggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class MainActivity : UtMortalActivity(), IUtActivityBrokerStoreProvider {
    override val logger = UtLog("Main")
    override val activityBrokers = UtActivityBrokerStore(this, UtOpenFilePicker(), UtCreateFilePicker(), UtMultiPermissionsBroker(), UtMediaFilePicker(), UtDirectoryPicker())
    private val binder = Binder()
    private lateinit var controls: ActivityMainBinding
    private val compatBackKeyDispatcher = CompatBackKeyDispatcher()

    class MediaSource private constructor(val file: AndroidFile, override val type:String) : IMediaSourceWithMutableChapterList {
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
            fun AndroidFile.getType():String? {
                return when (getContentType()) {
                    "video/mp4"-> "mp4"
                    "image/png"-> "png"
                    "image/jpeg"-> "jpg"
                    else-> null
                }
            }
            fun fromFile(file:AndroidFile, type:String): MediaSource {
//                val type = file.getType() ?: return null
                return MediaSource(file, type)
            }
        }
    }

    class MainViewModel(application: Application): AndroidViewModel(application) {
        init {
            UtLib.applicationContext = application
        }
        /**
         * OpenInで渡されたUriを保持するクラス
         * デバイスを回転するたびにインポート処理が
         */
        class OpenInInfo {
            var uri:Uri? = null
                private set
            var result:Boolean = false
                private set
            fun reset() {
                uri = null
                result = false
            }
            fun set(uri:Uri, result:Boolean) {
                this.uri = uri
                this.result = result
            }
            fun isHandled(uri:Uri):Boolean {
                return uri == this.uri
            }
        }
        val logger = UtLog("VM")
        val localData = LocalData(application)
        val projectDb = ProjectDB(application)

        val targetMediaSource = MutableStateFlow<MediaSource?>(null)
        val projectPanelOpened = MutableStateFlow<Boolean>(true)
//        val requestShowPanel = MutableStateFlow(true)

        var openInInfo = OpenInInfo()
        val projectListViewModel = ProjectListView.ViewModel(projectDb, application, ::saveCurrentProject)
        val projectName: StateFlow<String> get() = projectListViewModel.currentProjectName

//        val projectName = MutableStateFlow<String>("")
        val editorModel = MediaEditorModel.Builder(application, viewModelScope) {
                supportChapter(false)
                supportSnapshot(::snapshot)
                enableRotateLeft()
                enableRotateRight()
                enableSeekSmall(0, 0)
                enableSeekMedium(1000, 3000)
                enableSeekLarge(5000, 10000)
                enableSliderLock(true)
                enablePhotoViewer(photoSizeOption=PhotoSizeOption.Original)
                customPhotoLoader(StandardPhotoLoader())    // 画像ファイルのSHA1ハッシュをsignatureとしてGlideによる画像ロードを利用
            }
            .supportChapterEditor()
            .supportCrop()
            .setSaveFileHandler( GenericSaveFileHandler(application, true))
            .supportSplit(GenericSplitHandler(application, true))
            .setOutputFileProvider(CustomInteractiveOutputFileProvider(projectName))
            .setOutputFileSelector(CustomExportToDirectoryFileSelector(projectName))
            .enableBuiltInMagnifySlider()
            .build()

        fun snapshot(pos:Long, bitmap: RefBitmap) {
            UtImmortalTask.launchTask("MainViewModel.snapshot") {
                val time = if (pos > 0) TimeSpan(pos).run { if (hours > 0) "$hours.$minutes.$seconds}" else "$minutes.$seconds" } else ""
                val initialName = projectName.value.takeIf { it.lowercase().startsWith("img-") } ?: "img-${projectName.value}-$time"
                try {
                    SnapshotDialog.showBitmap(bitmap, initialName = initialName, editorModel.cropHandler.maskViewModel.getParams())
                    logger.debug("completed")
                } catch (e:Throwable) {
                    logger.error(e)
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            editorModel.close()
        }

        fun onProjectSelected(project: Project?): MediaSource? {
            if (project == null) {
                localData.currentProjectId = -1
                targetMediaSource.value = null
                editorModel.playerModel.setSource(null)
                editorModel.cropHandler.maskViewModel.setParams(MaskCoreParams.IDENTITY)
                return null
            }
            val fileUri = project.editingUri.toUri()
            val orgSource = targetMediaSource.value
            if (orgSource?.file?.safeUri == fileUri) return orgSource
            val source = MediaSource.fromFile(fileUri.toAndroidFile(getApplication()), project.type).apply {
                if (!isPhoto) {
                    chapterList.deserialize(project.serializedChapters)
                }
            }
            localData.currentProjectId = project.id
            targetMediaSource.value = source
            editorModel.playerModel.setSource(source)
//            requestShowPanel.value = false
            if (!project.serializedCropParams.isNullOrBlank()) {
                editorModel.cropHandler.maskViewModel.setParams(MaskCoreParams.fromJson(project.serializedCropParams))
            } else {
                editorModel.cropHandler.maskViewModel.setParams(MaskCoreParams.IDENTITY)
            }
            return source
        }

        suspend fun saveCurrentProject():Project? {
            logger.debug("saving")
            return logger.chronos {
                val project = projectListViewModel.currentProject.value ?: return@chronos null
                val source = targetMediaSource.value ?: return@chronos null
                withContext(Dispatchers.IO) {
                    logger.debug("saving")
                    projectDb.updateProjectVariables(
                        project,
                        projectName.value,
                        source.chapterList.serialize(),
                        editorModel.cropHandler.maskViewModel.getParams().serialize(),
                        editorModel.cropHandler.resolutionInt
                    )
                }
            }
        }

        fun storeToLocalData() {
            logger.debug("saving")
            val proj = projectListViewModel.currentProject.value
            if (proj!=null) {
                localData.apply {
                    currentProjectId = proj.id
                    val source = targetMediaSource.value
                    if (source != null && !source.isPhoto) {
                        playPosition = editorModel.playerModel.currentPosition
                        isPlaying = editorModel.playerModel.isPlaying.value
                    } else {
                        playPosition = 0
                        isPlaying = false
                    }
                }
            }
        }

        suspend fun restoreFromLocalData() {
            val id = localData.currentProjectId
            val proj = projectListViewModel.restoreSelection(id)
            val source = onProjectSelected(proj) ?: return
            if (source.isPhoto) return
            editorModel.playerModel.seekTo(localData.playPosition)
            if (localData.isPlaying) {
                editorModel.playerControllerModel.commandPlay.invoke()
            }
        }
    }

    private val viewModel by viewModels<MainViewModel>()
    private lateinit var gestureManager: UtScaleGestureManager
    val halfPanelWidth:Int by lazy { 300.dp.px(this) }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UtLogConfig.logLevel = Log.VERBOSE

        enableEdgeToEdge()
        controls = ActivityMainBinding.inflate(layoutInflater)
        controls.appVersion.text = "${if (BuildConfig.DEBUG) "D." else "v"}${PackageUtil.getVersion(this)}"
        setContentView(controls.root)
        setupWindowInsetsListener(controls.root)

        compatBackKeyDispatcher.register(this) {
            UtImmortalTask.launchTask {
                if (viewModel.editorModel.cropHandler.cancelMode()) {
                    return@launchTask
                }
                if (showYesNoMessageBox("Exit", "Are you sure to exit?")) {
                    withOwner {
                        viewModel.saveCurrentProject()
                        it.asActivity()?.finish()
                    }
                }
            }
        }
        val AnimDuration = 200L
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
                        Direction.Start -> viewModel.projectPanelOpened.value = false
                        Direction.End -> viewModel.projectPanelOpened.value = true
                    }
                }
                onFlickVertical {
                    val playerControlPanel = controls.editorPlayerView.controls.controller
                    val editorControlPanel = controls.editorPlayerView.controls.editorController
                    if (playerControlPanel.isVisible) {
                        // hide control panels
                        editorControlPanel.animate()
                            .y((-(editorControlPanel.height+editorControlPanel.paddingTop).toFloat()))
                            .setDuration(AnimDuration)
                            .start()
                        playerControlPanel.animate()
                            .y(controls.editorPlayerView.height.toFloat())
                            .setDuration(AnimDuration)
                            .withEndAction {
                                playerControlPanel.visibility = View.INVISIBLE
                            }
                            .start()

                    } else {
                        // show control panels
                        playerControlPanel.visibility = View.VISIBLE
                        editorControlPanel.animate()
                            .y(controls.editorPlayerView.paddingTop.toFloat())
                            .setDuration(AnimDuration)
                            .start()
                        playerControlPanel.animate()
                            .y((controls.editorPlayerView.height - controls.editorPlayerView.paddingBottom - playerControlPanel.height).toFloat())
                            .setDuration(AnimDuration)
                            .start()
                    }

                }
            }

        // ビューのレイアウトが完了したときに１度だけ実行するコールバックを設定する
        // 汎用的なので、どこかのライブラリに入れたい。
        fun onInitialLayout(callback:()->Unit) {
            val listener: ViewTreeObserver.OnGlobalLayoutListener
                = object: ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        callback()
                        controls.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            controls.root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }

//        var currentViewState: MainViewModel.ViewState? = null
        var isPanelOpened = viewModel.projectPanelOpened.value
        onInitialLayout {
            isPanelOpened = updateProjectPanel()
        }

        binder
            .owner(this)
            .observe(viewModel.projectPanelOpened) { vs ->
                if (isPanelOpened == vs) return@observe
                if (isPanelOpened) {
                    controls.buttonPane.setLayoutWidth(halfPanelWidth)
                    controls.buttonPane.animate()
                        .x(controls.root.paddingStart.toFloat())
                        .setDuration(AnimDuration)
                        .withEndAction {
                            isPanelOpened = updateProjectPanel()
                        }
                        .start()
                } else {
                        controls.buttonPane.setLayoutWidth(halfPanelWidth)
                        controls.buttonPane.animate()
                            .x(-controls.buttonPane.width.toFloat())
                            .setDuration(AnimDuration)
                            .withEndAction {
                                isPanelOpened = updateProjectPanel()
                            }
                            .start()
                    }
                }
            .visibilityBinding(controls.buttonPane, viewModel.editorModel.cropHandler.isCroppingNow, BoolConvert.Inverse)
            .clickBinding(controls.menuButton) {
                viewModel.projectPanelOpened.toggle()
            }
            .observe(viewModel.editorModel.cropHandler.isCroppingNow) {
                if (it) {
                    gestureManager.agent.resetScrollAndScale()
                }
                enableGestureManager(!it)
            }
            .add(viewModel.editorModel.saveFileHandler.listener.addOnSavedListener(this){ result->
                if (result.succeeded) {
                    UtImmortalTask.launchTask("save.succeeded") {
                        val target = result.outputFile as? AndroidFile
                        val name = target?.getFileName() ?: "unknown"
                        var message = "Saved in $name"
                        if (result is VideoSaveResult) {
                            val uri = target?.safeUri
                            val outLen = result.convertResult.report?.output?.size ?: 0L
                            val inLen = result.convertResult.report?.input?.size ?: 0L
                            if (outLen > 0L && inLen > 0L ) {
                                message += "  (${formatSize(inLen)} -> ${formatSize(outLen)})"
                            }
                            DetailMessageDialog.showMessage("Completed", message, result.convertResult.report?.toString(), uri?.toString(), null)
                        } else {
                            showConfirmMessageBox("Completed", message)
                        }
                    }
                }
                else if (result.failed) {
                    UtImmortalTask.launchTask("save.error") {
                        showConfirmMessageBox("Save Error", result.errorMessage?:result.error?.message?:"Unknown Error")
                    }
                }
            })
            .onViewSizeChanged(controls.menuButton) { width, _->
                controls.editorPlayerView.controls.editorController.setLayoutWidth((controls.root.width - width - 10.dp.px(this)).coerceAtLeast(50.dp.px(this)))
            }
        controls.editorPlayerView.bindViewModel(viewModel.editorModel, binder)
        controls.projectListView.bindViewModel(viewModel.projectListViewModel, binder)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  // スリープしない
        )

        UtImmortalTaskManager.immortalTaskScope.launch {
            activityBrokers.multiPermissionBroker.Request()
                .addIf(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .execute()
            viewModel.restoreFromLocalData()
            binder.observe(viewModel.projectListViewModel.currentProject) {
                viewModel.onProjectSelected(it)
            }
            acceptIncomingData()
        }
    }

    private suspend fun acceptIncomingData():Boolean {
        if (intent?.action == Intent.ACTION_SEND) {
            // 外部アプリから「送る」られた
            // intentからUriを取り出す。
            val uri = if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            } else null

            if (uri==null) {
                // 処理対象のUriではなかった
                viewModel.openInInfo.reset()
                return false
            }

            if (viewModel.openInInfo.isHandled(uri)) {
                // すでに処理済みのUri
                return viewModel.openInInfo.result
            }
            try {
                // URIから動画・画像をプロジェクトとして読み込む
                viewModel.projectListViewModel.addProjectWithMediaUri(uri).apply {
                    viewModel.openInInfo.set(uri, this)
                }
            } catch (e: Throwable) {
                logger.error(e)
                return false
            }
        }
        viewModel.openInInfo.reset()
        return false
    }

    private fun updateProjectPanel():Boolean {
        logger.debug("paddingStart=${controls.root.paddingStart}, paneWidth=${controls.buttonPane.width}")
        return viewModel.projectPanelOpened.value.apply {
            if (this) {
                controls.buttonPane.setLayoutWidth(halfPanelWidth)
                controls.buttonPane.x = controls.root.paddingStart.toFloat()
            } else {
                controls.buttonPane.setLayoutWidth(halfPanelWidth)
                controls.buttonPane.x = -controls.buttonPane.width.toFloat()
            }
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

    override fun onPause() {
        super.onPause()
        viewModel.storeToLocalData()
        CoroutineScope(Dispatchers.IO).launch {
            viewModel.saveCurrentProject()
        }
    }
}