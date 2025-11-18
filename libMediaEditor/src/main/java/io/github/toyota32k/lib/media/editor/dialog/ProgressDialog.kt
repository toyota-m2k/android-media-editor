package io.github.toyota32k.lib.media.editor.dialog

import android.os.Bundle
import android.view.View
import androidx.lifecycle.viewModelScope
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.command.ReliableCommand
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.progressBarBinding
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.lib.media.editor.databinding.DialogProgressBinding
import io.github.toyota32k.lib.media.editor.handler.save.ICanceller
import io.github.toyota32k.lib.media.editor.handler.save.IProgressSink
import io.github.toyota32k.lib.media.editor.handler.save.SaveTaskStatus
import io.github.toyota32k.media.lib.converter.IProgress
import io.github.toyota32k.media.lib.converter.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.also
import kotlin.ranges.coerceIn

class ProgressDialog : UtDialogEx() {
    class ProgressSink(val viewModel: ProgressViewModel, canceller:ICanceller?) : IProgressSink {
        init {
            if (canceller!=null) {
                viewModel.cancelCommand.bindForever { canceller.cancel() }
            }
        }
        fun <T> MutableStateFlow<T>.update(value:T) {
            if (this.value != value) {
                this.value = value
            }
        }

        override fun onProgress(status: SaveTaskStatus, progress: IProgress) {
            viewModel.message.update(status.message)
            viewModel.progress.update(progress.percentage)
            viewModel.progressText.update(progress.format())
        }

        override fun complete() {
            viewModel.viewModelScope.launch(Dispatchers.Main) {
                viewModel.closeCommand.invoke(true)
            }
        }
    }

    class ProgressViewModel : UtDialogViewModel() {
        val progress = MutableStateFlow(0)
        val progressText = MutableStateFlow("")
        val title = MutableStateFlow("")
        val message = MutableStateFlow("")
        val cancelCommand = LiteUnitCommand()
        val closeCommand = ReliableCommand<Boolean>()

        fun sizeInKb(size: Long): String {
            return String.format(Locale.US, "%,d KB", size / 1000L)
        }

        fun setProgress(current:Long, total:Long):Int {
            val percent = if (total <= 0L) 0 else (current * 100L / total).toInt().coerceIn(0,100)
            progress.value = percent
            progressText.value = "${sizeInKb(current)} / ${sizeInKb(total)} (${percent} %)"
            return percent
        }
    }

    private val viewModel by lazy { getViewModel<ProgressViewModel>() }
    lateinit var controls: DialogProgressBinding

    override fun preCreateBodyView() {
        gravityOption = GravityOption.CENTER
        noHeader = true
        noFooter = true
        widthOption = WidthOption.LIMIT(400)
        heightOption = HeightOption.COMPACT
        cancellable = false
    }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogProgressBinding.inflate(inflater.layoutInflater)
        return controls.root.also { _->
            binder
                .textBinding(controls.message, viewModel.message)
                .textBinding(controls.progressText, viewModel.progressText)
                .dialogTitle(viewModel.title)
                .progressBarBinding(controls.progressBar, viewModel.progress)
                .bindCommand(viewModel.cancelCommand, controls.cancelButton)
                .bindCommand(viewModel.closeCommand) { if(it) onPositive() else onNegative() }
        }
    }

    companion object {
        suspend fun showProgressDialog(title:String, cancel:ICanceller?) : ProgressSink {
            val sink = MutableStateFlow<ProgressSink?>(null)
            UtImmortalTask.launchTask("AmeProgressDialog") {
                val vm = createViewModel<ProgressViewModel>()
                vm.title.value = title
                sink.value = ProgressSink(vm, cancel)
                showDialog(taskName) { ProgressDialog() }
            }
            return sink.filterNotNull().first()
        }
    }
}