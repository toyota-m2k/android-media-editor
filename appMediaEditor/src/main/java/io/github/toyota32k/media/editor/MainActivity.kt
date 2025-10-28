package io.github.toyota32k.media.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.observe
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.dialog.broker.IUtActivityBrokerStoreProvider
import io.github.toyota32k.dialog.broker.UtActivityBrokerStore
import io.github.toyota32k.dialog.broker.pickers.IUtFilePickerStoreProvider
import io.github.toyota32k.dialog.broker.pickers.UtCreateFilePicker
import io.github.toyota32k.dialog.broker.pickers.UtOpenFilePicker
import io.github.toyota32k.dialog.mortal.UtMortalActivity
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.lib.media.editor.output.AbstractSaveFileHandler
import io.github.toyota32k.lib.media.editor.model.AbstractSplitHandler
import io.github.toyota32k.lib.media.editor.model.IMediaSourceWithMutableChapterList
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.lib.media.editor.output.DefaultAudioStrategySelector
import io.github.toyota32k.lib.media.editor.output.GenericSaveFileHandler
import io.github.toyota32k.lib.media.editor.output.SingleVideoStrategySelector
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.lib.player.model.chapter.MutableChapterList
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.logger.UtLogConfig
import io.github.toyota32k.media.editor.databinding.ActivityMainBinding
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.Converter
import io.github.toyota32k.media.lib.converter.toAndroidFile
import io.github.toyota32k.media.lib.strategy.PresetVideoStrategies
import io.github.toyota32k.utils.android.CompatBackKeyDispatcher
import io.github.toyota32k.utils.android.setLayoutWidth
import io.github.toyota32k.utils.toggle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

class MainActivity : UtMortalActivity(), IUtActivityBrokerStoreProvider {
    override val logger = UtLog("Main")
    override val activityBrokers = UtActivityBrokerStore(this, UtOpenFilePicker(), UtCreateFilePicker())
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
        inner class SplitHandler : AbstractSplitHandler(true) {
            override suspend fun splitVideoAt(targetSource: IMediaSource, positionMs: Long): Boolean {
                TODO("Not yet implemented")
            }

        }

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
                    SingleVideoStrategySelector(PresetVideoStrategies.AVC720Profile),
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
        }
    }

    private val viewModel by viewModels<MainViewModel>()

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

        val AnimDuration = 200L
        binder
            .owner(this)
            .observe(viewModel.viewState) { vs ->
                when (vs) {
                    MainViewModel.ViewState.FULL -> {
                        controls.buttonPane.setLayoutWidth(ViewGroup.LayoutParams.MATCH_PARENT)
                        controls.buttonPane.animate()
                            .x(0f)
                            .setDuration(AnimDuration)
                            .start()
                    }

                    MainViewModel.ViewState.HALF -> {
                        controls.buttonPane.setLayoutWidth(ViewGroup.LayoutParams.WRAP_CONTENT)
                        controls.buttonPane.animate()
                            .x(0f)
                            .setDuration(AnimDuration)
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
            .visibilityBinding(controls.menuButton, combine(viewModel.isEditing, viewModel.editorModel.cropHandler.croppingNow) { isEditing, cropping ->
                isEditing && !cropping
            })
            .clickBinding(controls.menuButton) {
                viewModel.requestShowPanel.toggle()
            }
            .clickBinding(controls.buttonOpen) {
                openMediaFile()
            }
            .clickBinding(controls.buttonClose) {
                viewModel.targetMediaSource.value = null
            }
        controls.editorPlayerView.bindViewModel(viewModel.editorModel, binder)
    }


    private fun openMediaFile() {
        UtImmortalTask.launchTask {
            val file = activityBrokers.openFilePicker.selectFile(arrayOf("video/*", "image/*"))
            if (file != null) {
                val source = MediaSource.fromFile(file.toAndroidFile(applicationContext)) ?: return@launchTask
                viewModel.targetMediaSource.value = source
                viewModel.editorModel.playerModel.setSource(source)
                viewModel.requestShowPanel.value = false
            }
        }
    }
}