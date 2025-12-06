package io.github.toyota32k.media.editor.dialog

import android.app.Application
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import io.github.toyota32k.binder.BindingMode
import io.github.toyota32k.binder.BoolConvert
import io.github.toyota32k.binder.IIDValueResolver
import io.github.toyota32k.binder.checkBinding
import io.github.toyota32k.binder.materialRadioButtonGroupBinding
import io.github.toyota32k.binder.multiEnableBinding
import io.github.toyota32k.binder.multiVisibilityBinding
import io.github.toyota32k.binder.radioGroupBinding
import io.github.toyota32k.dialog.UtDialog
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtAndroidViewModel
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.application
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.media.editor.databinding.DialogWallpaperBinding
import io.github.toyota32k.media.editor.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.CancellationException

class WallpaperDialog : UtDialogEx() {
    class WallpaperViewModel() : UtDialogViewModel() {
        enum class Target(@param:IdRes val id:Int) {
            WALLPAPER(R.id.radio_wallpaper),
            FILE(R.id.radio_file),
            ;
            companion object {
                fun valueOf(@IdRes id: Int): Target? {
                    return entries.find { it.id == id }
                }
            }
            object IDResolver : IIDValueResolver<Target> {
                override fun id2value(id: Int): Target? = valueOf(id)
                override fun value2id(v: Target): Int = v.id
            }
        }
        enum class FileStore(@param:IdRes val id:Int) {
            SAVE_FILE(R.id.save_file),
            SAVE_IN_ALBUM(R.id.save_in_album),
            ;
            companion object {
                fun valueOf(@IdRes id: Int): FileStore? {
                    return entries.find { it.id == id }
                }
            }
            object IDResolver : IIDValueResolver<FileStore> {
                override fun id2value(id: Int): FileStore? = valueOf(id)
                override fun value2id(v: FileStore): Int = v.id
            }
        }

        val target = MutableStateFlow(Target.WALLPAPER)
        val fileStore = MutableStateFlow(FileStore.SAVE_FILE)

//        val saveFile = fileStore.map { it == FileStore.SAVE_FILE }
//        val saveInAlbum = fileStore.map { it== FileStore.SAVE_IN_ALBUM }
        val displayName = MutableStateFlow<String>("")
        val lockScreen = MutableStateFlow(false)
        val homeScreen = MutableStateFlow(false)

        val useCropHint = MutableStateFlow(false)
        val isReady = combine(target, fileStore, lockScreen, homeScreen, displayName) {
            target, fileStore, lockScreen, homeScreen, displayName->
            if (target==Target.WALLPAPER) {
                lockScreen || homeScreen
            } else {
                fileStore==FileStore.SAVE_FILE || displayName.isNotBlank()
            }
        }
    }

    private lateinit var controls: DialogWallpaperBinding
    private val viewModel: WallpaperViewModel by lazy { getViewModel() }

    override fun preCreateBodyView() {
        leftButtonType = ButtonType.CANCEL
        rightButtonType = ButtonType.DONE
        title = "Wallpaper"
        gravityOption = GravityOption.CENTER
        widthOption = WidthOption.COMPACT
        heightOption = HeightOption.COMPACT
    }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogWallpaperBinding.inflate(inflater.layoutInflater)
        binder
            .owner(this)
            .materialRadioButtonGroupBinding(controls.radioGroupTargets, viewModel.target, WallpaperViewModel.Target.IDResolver, BindingMode.TwoWay)
            .radioGroupBinding(controls.fileRadioGroup, viewModel.fileStore, WallpaperViewModel.FileStore.IDResolver, BindingMode.TwoWay)

            .checkBinding(controls.lockScreen, viewModel.lockScreen)
            .checkBinding(controls.homeScreen, viewModel.homeScreen)
            .checkBinding(controls.useCropHint, viewModel.useCropHint)

            .multiVisibilityBinding(arrayOf(controls.lockScreen, controls.homeScreen, controls.useCropHint), viewModel.target.map { it == WallpaperViewModel.Target.WALLPAPER } )
            .multiVisibilityBinding(arrayOf(controls.fileRadioGroup, controls.fileName), viewModel.target.map { it == WallpaperViewModel.Target.FILE } )

            .dialogRightButtonEnable(viewModel.isReady)
        return controls.root
    }
}