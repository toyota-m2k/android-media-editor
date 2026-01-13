package io.github.toyota32k.media.editor

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.BoolConvert
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.editTextBinding
import io.github.toyota32k.binder.multiVisibilityBinding
import io.github.toyota32k.binder.observe
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
import io.github.toyota32k.dialog.task.UtImmortalTaskBase
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.dialog.task.showConfirmMessageBox
import io.github.toyota32k.dialog.task.showYesNoMessageBox
import io.github.toyota32k.lib.media.editor.dialog.NameDialog
import io.github.toyota32k.lib.media.editor.handler.FileUtil
import io.github.toyota32k.lib.media.editor.model.IMediaSourceWithMutableChapterList
import io.github.toyota32k.lib.media.editor.model.MaskCoreParams
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.lib.media.editor.handler.save.GenericSaveFileHandler
import io.github.toyota32k.lib.media.editor.handler.save.VideoSaveResult
import io.github.toyota32k.lib.media.editor.handler.split.GenericSplitHandler
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.lib.player.model.StandardPhotoLoader
import io.github.toyota32k.lib.player.model.chapter.MutableChapterList
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.logger.UtLogConfig
import io.github.toyota32k.media.editor.MainActivity.MediaSource.Companion.getType
import io.github.toyota32k.media.editor.databinding.ActivityMainBinding
import io.github.toyota32k.media.editor.dialog.DetailMessageDialog
import io.github.toyota32k.media.editor.dialog.ProjectManagerDialog
import io.github.toyota32k.media.editor.dialog.SnapshotDialog
import io.github.toyota32k.media.editor.project.Project
import io.github.toyota32k.media.editor.project.ProjectDB
import io.github.toyota32k.media.editor.providers.CustomExportToDirectoryFileSelector
import io.github.toyota32k.media.editor.providers.CustomInteractiveOutputFileProvider
import io.github.toyota32k.media.lib.io.AndroidFile
import io.github.toyota32k.media.lib.io.toAndroidFile
import io.github.toyota32k.utils.TimeSpan
import io.github.toyota32k.utils.android.CompatBackKeyDispatcher
import io.github.toyota32k.utils.android.PackageUtil
import io.github.toyota32k.utils.android.RefBitmap
import io.github.toyota32k.utils.android.dp
import io.github.toyota32k.utils.android.setLayoutWidth
import io.github.toyota32k.utils.gesture.Direction
import io.github.toyota32k.utils.gesture.UtScaleGestureManager
import io.github.toyota32k.utils.toggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
                    else-> return null
                }
            }
            fun fromFile(file:AndroidFile, type:String):MediaSource? {
//                val type = file.getType() ?: return null
                return MediaSource(file, type)
            }
        }
    }

    class MainViewModel(application: Application): AndroidViewModel(application) {
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
        val isEditing = targetMediaSource.map { it!=null }
        var openInInfo = OpenInInfo()
        val requestShowPanel = MutableStateFlow(true)
        val projectName = MutableStateFlow<String>("")
        val editorModel = MediaEditorModel.Builder(application, viewModelScope) {
                supportChapter(false)
                supportSnapshot(::snapshot)
                enableRotateLeft()
                enableRotateRight()
                enableSeekSmall(0, 0)
                enableSeekMedium(1000, 3000)
                enableSeekLarge(5000, 10000)
                enableSliderLock(true)
                enablePhotoViewer()
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
            viewModelScope.launch {
                val time = if (pos>0) TimeSpan(pos).run { if(hours>0) "$hours.$minutes.$seconds}" else "$minutes.$seconds"} else ""
                val initialName = projectName.value.takeIf { it.lowercase().startsWith("img-") } ?: "img-${projectName.value}-$time"
                runCatching { SnapshotDialog.showBitmap(bitmap, initialName = projectName.value,  editorModel.cropHandler.maskViewModel.getParams()) }.onFailure { e-> logger.error(e) }
            }
        }

//        val commandSaveFile = LiteUnitCommand {
//            viewModelScope.launch {
//                editorModel.playerControllerModel.commandPause.invoke()
//                storeToLocalData()
//                editorModel.saveFile()
//            }
//        }

        val commandOpenFile = LiteUnitCommand {
            UtImmortalTask.launchTask("commandOpenFile") {
                val file = withActivity<MainActivity,Uri?> { activity->
                    activity.activityBrokers.openFilePicker.selectFile(arrayOf("video/*", "image/*"))
                }
                if (file != null) {
                    setNewTargetMediaFile(file)
                }
            }
        }

        val commandOpenProject = LiteUnitCommand {
            UtImmortalTask.launchTask("commandOpenProject") {
                saveCurrentProject()
                val result = ProjectManagerDialog.show(projectDb, targetMediaSource.value?.uri) ?: return@launchTask
                val project = result.selectedProject
                if (result.removeCurrentProject) {
                    closeCurrentProject()
                    if (project==null) {
                        return@launchTask
                    }
                }

                if (project!=null) {
                    setTargetMediaFile(project)
                } else {
                    commandOpenFile.invoke()
                }
            }
        }

        private suspend fun UtImmortalTaskBase.closeCurrentProject() {
            if (editorModel.isDirty) {
                if (!showYesNoMessageBox("Close Project", "Are you sure to abort your changes?")) {
                    return
                }
            } else {
                if (!showYesNoMessageBox("Close Project", "Are you sure to close the project?")) {
                    return
                }
            }
            withContext(Dispatchers.IO) {
                val project = projectDb.getProject(localData.currentProjectId)
                localData.currentProjectId = -1
                targetMediaSource.value = null
                if (project != null) {
                    projectDb.unregisterProject(project)
                }
            }
        }

        val commandCloseProject = LiteUnitCommand {
            UtImmortalTask.launchTask("closeEditingFile") {
                closeCurrentProject()
            }
        }

        override fun onCleared() {
            super.onCleared()
            editorModel.close()
        }

        // buttonPane のv表示ステータス
        enum class ViewState {
            FULL,       // - buttonPane全面表示
            HALF,       // - buttonPane控えめ表示
            NONE,       // - buttonPane非表示
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

        fun showErrorMessage(message:String) {
            UtImmortalTask.launchTask("error.message") {
                showConfirmMessageBox("Error", message)
            }
        }
        fun <T> handleError(message:String, ret:T):T {
            UtImmortalTask.launchTask("error.message") {
                showConfirmMessageBox("Error", message)
            }
            return ret
        }

        suspend fun setNewTargetMediaFile(fileUri:Uri):Boolean {
            val project = withContext(Dispatchers.IO) { projectDb.getProject(fileUri) }
            return if (project!=null) {
                // reopen
                setTargetMediaFile(project) ?: showErrorMessage("Cannot open media file.")
                true
            } else {
                // new file
                val file = fileUri.toAndroidFile(application)
                val name = FileUtil.getBaseName(file).takeIf { !it.isNullOrBlank() } ?: "noname"
                val projectName = NameDialog.show(name, "New Project", "Project Name") ?: return false
                val project = withContext(Dispatchers.IO) {
                    projectDb.registerProject(
                        projectName,
                        fileUri.toString(),
                        file.getType() ?: "mp4",
                        null, null, 0)
                } ?: return handleError("Cannot register uri.",false)
                setTargetMediaFile(project) ?: showErrorMessage("Cannot create project.")
                true
            }
        }

        suspend fun setTargetMediaFile(project: Project):MediaSource? {
            saveCurrentProject()

            val fileUri = project.uri.toUri()
            val orgSource = targetMediaSource.value
            if (orgSource?.file?.safeUri == fileUri) return orgSource
            val source = MediaSource.fromFile(fileUri.toAndroidFile(getApplication()), project.type)?.apply {
                if (!isPhoto) {
                    chapterList.deserialize(project.serializedChapters)
                }
            } ?: return null
            projectName.value = project.name
            localData.currentProjectId = project.id
            targetMediaSource.value = source
            editorModel.playerModel.setSource(source)
            requestShowPanel.value = false
            if (!project.serializedCropParams.isNullOrBlank()) {
                editorModel.cropHandler.maskViewModel.setParams(MaskCoreParams.fromJson(project.serializedCropParams))
            }
            return source
        }


        suspend fun saveCurrentProject():Project? {
            logger.debug("saving")
            return logger.chronos {
                val source = targetMediaSource.value ?: return null
                withContext(Dispatchers.IO) {
                    logger.debug("saving")
                    projectDb.registerProject(
                        projectName.value,
                        source.uri,
                        source.type,
                        source.chapterList.serialize(),
                        editorModel.cropHandler.maskViewModel.getParams().serialize(),
                                editorModel.cropHandler.resolutionInt
                    )
                }
            }
        }

        suspend fun storeToLocalData() {
            logger.debug("saving")
            val proj = saveCurrentProject()
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
            val proj = withContext(Dispatchers.IO) { projectDb.getProject(id) } ?: return
            val source = setTargetMediaFile(proj) ?: return
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UtLogConfig.logLevel = Log.VERBOSE

        enableEdgeToEdge()
        controls = ActivityMainBinding.inflate(layoutInflater)
//        controls.appName.text = "${getString(R.string.app_name)} - v${PackageUtil.getVersion(this)?:"?"}${if(BuildConfig.DEBUG) " (D)" else ""}"
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
                        it.asActivity()?.finish()
                    }
                }
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

        var currentViewState: MainViewModel.ViewState? = null
        onInitialLayout {
            currentViewState = updateButtonPanel()
        }

        val AnimDuration = 200L
        binder
            .owner(this)
            .observe(viewModel.viewState) { vs ->
                if (currentViewState==null || vs == currentViewState) return@observe
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
                                currentViewState = updateButtonPanel()
                            }
                            .start()
                    }

                    MainViewModel.ViewState.HALF -> {
                        controls.buttonPane.setLayoutWidth(halfPanelWidth)
                        controls.buttonPane.animate()
                            .x(controls.root.paddingStart.toFloat())
                            .setDuration(AnimDuration)
                            .withEndAction {
                                currentViewState = updateButtonPanel()
                            }
                            .start()
                    }

                    MainViewModel.ViewState.NONE -> {
                        controls.buttonPane.setLayoutWidth(halfPanelWidth)
                        controls.buttonPane.animate()
                            .x(-controls.buttonPane.width.toFloat())
                            .setDuration(AnimDuration)
                            .withEndAction {
                                currentViewState = updateButtonPanel()
                            }
                            .start()
                    }
                }
            }
            .editTextBinding(controls.projectNameEdit, viewModel.projectName)
            .visibilityBinding(controls.menuButton, combine(viewModel.isEditing, viewModel.editorModel.cropHandler.croppingNow) { isEditing, cropping ->
                isEditing && !cropping
            })
            .multiVisibilityBinding(arrayOf(controls.buttonClose, controls.projectNameEdit, controls.projectNameLabel), viewModel.isEditing)
            .visibilityBinding(controls.buttonPane, viewModel.editorModel.cropHandler.croppingNow, BoolConvert.Inverse)
            .clickBinding(controls.menuButton) {
                viewModel.requestShowPanel.toggle()
            }
            .bindCommand(viewModel.commandOpenProject, controls.buttonOpen)
//            .bindCommand(viewModel.commandSaveFile, controls.buttonSave)
            .bindCommand(viewModel.commandCloseProject,controls.buttonClose)
            .observe(viewModel.editorModel.cropHandler.croppingNow) {
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
                        val message = "Saved in $name"
                        if (result is VideoSaveResult) {
                            val uri = target?.safeUri
                            if (DetailMessageDialog.showMessage("Completed", message, result.convertResult.report?.toString(), uri?.toString(), null)) {
                                if (uri!=null) {
                                    viewModel.setNewTargetMediaFile(uri)
                                }
                            }
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
        controls.editorPlayerView.bindViewModel(viewModel.editorModel, binder)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  // スリープしない
        )

        UtImmortalTaskManager.immortalTaskScope.launch {
            activityBrokers.multiPermissionBroker.Request()
                .addIf(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .execute()
            if (!acceptIncomingData()) {
                viewModel.restoreFromLocalData()
            }
        }
    }

    private suspend fun acceptIncomingData():Boolean {
        if (intent?.action == Intent.ACTION_SEND) {
            // 外部アプリから「送る」られた
            // intentからUriを取り出す。
            val uri = if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
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
                viewModel.setNewTargetMediaFile(uri).apply {
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

    private fun updateButtonPanel(): MainViewModel.ViewState {
        logger.debug("paddingStart=${controls.root.paddingStart}, paneWidth=${controls.buttonPane.width}")
        return viewModel.viewState.value.apply {
            when (this) {
                MainViewModel.ViewState.FULL -> {
                    controls.buttonPane.setLayoutWidth(ViewGroup.LayoutParams.MATCH_PARENT)
                    controls.buttonPane.x = controls.root.paddingStart.toFloat()
                }
                MainViewModel.ViewState.HALF -> {
                    controls.buttonPane.setLayoutWidth(halfPanelWidth)
                    controls.buttonPane.x = controls.root.paddingStart.toFloat()
                }
                MainViewModel.ViewState.NONE -> {
                    controls.buttonPane.setLayoutWidth(halfPanelWidth)
                    controls.buttonPane.x = -controls.buttonPane.width.toFloat()
                }

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

    private fun openMediaFile() {
        UtImmortalTask.launchTask {
            val file = activityBrokers.openFilePicker.selectFile(arrayOf("video/*", "image/*"))
            if (file != null) {
                viewModel.setNewTargetMediaFile(file)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.viewModelScope.launch { viewModel.storeToLocalData() }
    }
}