package io.github.toyota32k.lib.media.editor.dialog

import android.os.Bundle
import android.view.View
import io.github.toyota32k.binder.IIDValueResolver
import io.github.toyota32k.binder.editTextBinding
import io.github.toyota32k.binder.enableBinding
import io.github.toyota32k.binder.radioGroupBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.lib.media.editor.R
import io.github.toyota32k.lib.media.editor.databinding.DialogSaveOptionBinding
import io.github.toyota32k.lib.media.editor.dialog.SaveOptionDialog.SaveOptionViewModel.TargetType
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.lib.media.editor.handler.ExportFileProvider
import io.github.toyota32k.lib.media.editor.handler.NamedMediaFileProvider
import io.github.toyota32k.lib.media.editor.handler.OverwriteFileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class SaveOptionDialog : UtDialogEx() {
    class SaveOptionViewModel : UtDialogViewModel() {
        enum class TargetType(val resId: Int) {
            OVERWRITE(R.id.radio_overwrite),
            SAVE_MEDIA_FILE_AS(R.id.radio_save_as),
            EXPORT_FILE(R.id.radio_export),
            ;
            ;
            object IDResolver: IIDValueResolver<TargetType> {
                override fun id2value(id:Int) : TargetType {
                    return valueOf(id)
                }
                override fun value2id(v: TargetType): Int {
                    return v.resId
                }
            }
            companion object {
                fun valueOf(resId: Int, def: TargetType = EXPORT_FILE): TargetType {
                    return entries.find { it.resId == resId } ?: def
                }
                fun valueOf(name:String?):TargetType? {
                    if(name==null) return null
                    return try {
                        java.lang.Enum.valueOf(TargetType::class.java, name)
                    } catch (_:Throwable) {
                        null
                    }
                }
            }
        }
        val targetType = MutableStateFlow<TargetType>(TargetType.EXPORT_FILE)
        val isSaveAs = targetType.map { it == TargetType.SAVE_MEDIA_FILE_AS }
        val targetName = MutableStateFlow<String>("")
    }

    val viewModel: SaveOptionViewModel by lazy { getViewModel<SaveOptionViewModel>() }
    lateinit var controls: DialogSaveOptionBinding

    override fun preCreateBodyView() {
        title = "Save File"
        widthOption = WidthOption.LIMIT(300)
        heightOption = HeightOption.COMPACT
        rightButtonType = ButtonType.OK
        leftButtonType = ButtonType.CANCEL
    }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogSaveOptionBinding.inflate(inflater.layoutInflater)
        binder.owner(this)
            .editTextBinding(controls.nameText, viewModel.targetName)
            .enableBinding(controls.nameText, viewModel.isSaveAs)
            .radioGroupBinding(controls.radioGroupOptions, viewModel.targetType, SaveOptionViewModel.TargetType.IDResolver)
            .dialogRightButtonEnable(combine(viewModel.targetType, viewModel.targetName) { type, name-> type!=TargetType.SAVE_MEDIA_FILE_AS || name.isNotBlank() })
        return controls.root
    }

    companion object {
        suspend fun show(initialName:String, subFolder:String?, suffix:String): IOutputFileProvider? {
            return UtImmortalTask.awaitTaskResult {
                val vm = createViewModel<SaveOptionViewModel> {
                    targetName.value = initialName
                }
                if (showDialog(taskName) { SaveOptionDialog() }.status.ok) {
                    when (vm.targetType.value) {
                        SaveOptionViewModel.TargetType.EXPORT_FILE-> ExportFileProvider(suffix)
                        SaveOptionViewModel.TargetType.SAVE_MEDIA_FILE_AS-> NamedMediaFileProvider(vm.targetName.value, subFolder)
                        SaveOptionViewModel.TargetType.OVERWRITE-> OverwriteFileProvider()
                    }
                } else null
            }
        }
    }
}