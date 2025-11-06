package io.github.toyota32k.lib.media.editor.dialog

import android.os.Bundle
import android.view.View
import io.github.toyota32k.binder.editTextBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtAndroidViewModel.Companion.createAndroidViewModel
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.lib.media.editor.databinding.DialogNameBinding
import kotlinx.coroutines.flow.MutableStateFlow

class NameDialog : UtDialogEx() {
    class NameDialogViewModel : UtDialogViewModel() {
        var title: String? = null
        var hint: String? = null
        val name = MutableStateFlow<String>("")
    }
    private val viewModel by lazy { getViewModel<NameDialogViewModel>() }
    private lateinit var controls: DialogNameBinding

    override fun preCreateBodyView() {
        widthOption = WidthOption.LIMIT(300)
        heightOption = HeightOption.COMPACT
        title = viewModel.title ?: "Filename"
        rightButtonType = ButtonType.OK
        leftButtonType = ButtonType.CANCEL
    }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogNameBinding.inflate(inflater.layoutInflater)
        if (viewModel.hint!=null) {
            controls.nameText.hint = viewModel.hint
        }
        binder
            .owner(this)
            .editTextBinding(controls.nameText, viewModel.name)
        return controls.root
    }

    companion object {
        suspend fun show(initialName:String, title: String? = null, hint: String? = null): String? {
            return UtImmortalTask.awaitTaskResult(this::class.java.name) {
                val vm = createViewModel<NameDialogViewModel> {
                    this.name.value = initialName
                    this.hint = hint
                    this.title = title
                }
                if (showDialog(taskName) { NameDialog() }.status.ok) {
                    vm.name.value
                } else {
                    null
                }
            }
        }
    }
}