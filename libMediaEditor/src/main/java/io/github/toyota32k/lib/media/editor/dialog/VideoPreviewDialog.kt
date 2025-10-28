package io.github.toyota32k.lib.media.editor.dialog

import android.os.Bundle
import android.view.View
import androidx.lifecycle.viewModelScope
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.IUtImmortalTask
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.application
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.lib.media.editor.databinding.DialogVideoPreviewBinding
import io.github.toyota32k.lib.player.model.IChapter
import io.github.toyota32k.lib.player.model.IChapterList
import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IMediaSourceWithChapter
import io.github.toyota32k.lib.player.model.PlayerControllerModel
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.lib.player.model.chapter.ChapterList
import io.github.toyota32k.utils.gesture.UtGestureInterpreter
import io.github.toyota32k.utils.gesture.UtManipulationAgent
import io.github.toyota32k.utils.gesture.UtSimpleManipulationTarget
import java.util.concurrent.atomic.AtomicLong

class VideoPreviewDialog : UtDialogEx() {
    open class VideoSource(override val uri:String, override val name:String): IMediaSource {
        override val id: String
            get() = uri
        override val trimming: Range = Range.empty
        override val type: String = "mp4"
        override val startPosition: AtomicLong = AtomicLong(0)
    }
    class ChapterVideoSource(uri:String, name:String, val chapterList:IChapterList): VideoSource(uri, name), IMediaSourceWithChapter {
        override suspend fun getChapterList(): IChapterList {
            return chapterList
        }
    }

    class VideoPreviewViewModel() : UtDialogViewModel() {
        lateinit var videoSource: IMediaSource
        lateinit var playerControllerModel: PlayerControllerModel

        override fun onCleared() {
            super.onCleared()
            playerControllerModel.close()
        }

        companion object {
            fun create(task: IUtImmortalTask, uri:String, name:String, chapters: List<IChapter>?, setupPlayer:((PlayerControllerModel.Builder)->Unit)?): VideoPreviewViewModel {
                return task.createViewModel<VideoPreviewViewModel> {
                    val chapterList = if(chapters?.isNotEmpty()==true) ChapterList(chapters) else null
                    videoSource = if(chapterList!=null) ChapterVideoSource(uri,name,chapterList) else VideoSource(uri, name)
                    val builder = PlayerControllerModel.Builder(application, viewModelScope)
                        .autoPlay(true)
                    if (chapterList!=null) {
                        builder.supportChapter(true)
                    }
                    if (setupPlayer!=null) {
                        setupPlayer(builder)
                    }
                    playerControllerModel = builder.build()
                }
            }
        }
    }


    override fun preCreateBodyView() {
        widthOption = WidthOption.FULL
        heightOption = HeightOption.FULL
        noHeader = true
        leftButtonType = ButtonType.NONE
        rightButtonType = ButtonType.CLOSE
    }

    val viewModel by lazy { getViewModel<VideoPreviewViewModel>() }
    lateinit var controls: DialogVideoPreviewBinding
    private val gestureInterpreter = UtGestureInterpreter(context.applicationContext, enableScaleEvent = true)
    private val manipulationAgent by lazy { UtManipulationAgent(UtSimpleManipulationTarget(controls.videoViewer, controls.videoViewer.controls.player)) }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogVideoPreviewBinding.inflate(inflater.layoutInflater)
        controls.videoViewer.bindViewModel(viewModel.playerControllerModel, binder)
        viewModel.playerControllerModel.playerModel.setSource(viewModel.videoSource)

        gestureInterpreter.setup(this, manipulationAgent.parentView) {
            onScale(manipulationAgent::onScale)
            onScroll(manipulationAgent::onScroll)
            onTap {
                viewModel.playerControllerModel.playerModel.togglePlay()
            }
            onDoubleTap {
                manipulationAgent.resetScrollAndScale()
            }
        }

        return controls.root
    }

    companion object {
        suspend fun show(uri: String, name: String, chapters: List<IChapter>?=null, setupPlayer:((PlayerControllerModel.Builder)->Unit)?=null) {
            UtImmortalTask.awaitTask {
                VideoPreviewViewModel.create(this, uri, name, chapters, setupPlayer)
                showDialog(VideoPreviewDialog())
            }
        }
    }
}