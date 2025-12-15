package io.github.toyota32k.media.editor.dialog

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import io.github.toyota32k.binder.VisibilityBinding
import io.github.toyota32k.binder.checkBinding
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.immortalTaskContext
import io.github.toyota32k.dialog.task.launchSubTask
import io.github.toyota32k.lib.media.editor.dialog.VideoPreviewDialog
import io.github.toyota32k.lib.player.model.IChapter
import io.github.toyota32k.media.editor.R
import io.github.toyota32k.media.editor.databinding.DialogDetailMessageBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class DetailMessageDialog : UtDialogEx() {
    class DetailMessageViewModel : UtDialogViewModel() {
        val label = MutableStateFlow("")
        val message = MutableStateFlow<String>("")
        val detailMessage = MutableStateFlow<String?>(null)
        val showDetailMessage = MutableStateFlow(false)
        var targetUri: String? = null
        var chapters: List<IChapter>? = null
        val commandPlay = LiteUnitCommand {
            val targetUri = this.targetUri ?: return@LiteUnitCommand
            immortalTaskContext.launchSubTask {
                VideoPreviewDialog.show(targetUri, "preview", chapters) { builder ->
                    builder.enableSeekSmall(0,0)    // step by frame
                    builder.enableSeekMedium(1000, 1000)
                }
            }
        }

        companion object {
            fun create(taskName:String, label: String, message: String, detailMessage: String?, targetUri:String?, chapters: List<IChapter>?): DetailMessageViewModel {
                return UtImmortalTaskManager.taskOf(taskName)?.task?.createViewModel<DetailMessageViewModel>()?.also {
                    it.label.value = label
                    it.message.value = message
                    it.detailMessage.value = detailMessage
                    it.targetUri = targetUri
                    it.chapters = chapters
                } ?: throw kotlin.IllegalStateException("no task")
            }
            fun instanceFor(dlg:DetailMessageDialog): DetailMessageViewModel {
                return ViewModelProvider(dlg.immortalTaskContext, ViewModelProvider.NewInstanceFactory())[DetailMessageViewModel::class.java]
            }
        }
    }

    private val viewModel by lazy { DetailMessageViewModel.instanceFor(this) }
    private lateinit var controls : DialogDetailMessageBinding

    override fun preCreateBodyView() {
        gravityOption = GravityOption.CENTER
        widthOption = WidthOption.LIMIT(400)
        heightOption = HeightOption.AUTO_SCROLL
        leftButtonType = ButtonType.NEGATIVE_CLOSE
        rightButtonType = ButtonType(getString(R.string.open_as_project), positive=true)
        if (viewModel.targetUri!=null) {
            optionButtonType = ButtonType("Play", positive = true)
        }
        optionButtonWithAccent = true
        cancellable = false
        noHeader = true
    }

    override fun createBodyView(
        savedInstanceState: Bundle?,
        inflater: IViewInflater
    ): View {
        controls = DialogDetailMessageBinding.inflate(inflater.layoutInflater).apply {
            binder
                .textBinding(label, viewModel.label)
                .textBinding(message, viewModel.message)
                .textBinding(detailMessage, viewModel.detailMessage.map { it ?: ""})
                .checkBinding(checkShowDetail, viewModel.showDetailMessage)
                .visibilityBinding(checkShowDetail, viewModel.detailMessage.map { !it.isNullOrBlank() }, hiddenMode = VisibilityBinding.HiddenMode.HideByGone)
                .visibilityBinding(detailMessage, viewModel.showDetailMessage)
                .dialogOptionButtonCommand(viewModel.commandPlay)
        }

        return controls.root
    }

    companion object {
        suspend fun showMessage(label:String, message:String, detailMessage:String?, targetUri:String?, chapters: List<IChapter>?):Boolean {
            return UtImmortalTask.awaitTaskResult(DetailMessageDialog::class.java.name) {
                DetailMessageViewModel.create(taskName, label, message, detailMessage, targetUri, chapters)
                showDialog(taskName) { DetailMessageDialog() }.status.ok
            }
        }
    }
}