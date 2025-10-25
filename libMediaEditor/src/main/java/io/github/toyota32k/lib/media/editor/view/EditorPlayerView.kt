package io.github.toyota32k.lib.media.editor.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.VisibilityBinding
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.multiVisibilityBinding
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.lib.media.editor.databinding.EditorPlayerViewBinding
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.EditorPlayerViewAttributes
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.utils.gesture.IUtManipulationTarget
import kotlinx.coroutines.flow.MutableStateFlow

class EditorPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : FrameLayout(context, attrs, defStyleAttr) {
    val logger get() = AmeGlobal.logger
    val controls = EditorPlayerViewBinding.inflate(LayoutInflater.from(context), this, true)

    private fun setVideoPlayerViewAttributes(context:Context, attrs: AttributeSet?, defStyleAttr:Int) {
        EditorPlayerViewAttributes(context, attrs, defStyleAttr).use { epa->
            if (epa.sarForPlayer.sa.getBoolean(io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampAttrsByParent, true)) {
                controls.player.setPlayerAttributes(epa)
                controls.controller.setControlPanelAttributes(epa.sarForPlayer)
            }
        }
    }

    init {
        setVideoPlayerViewAttributes(context, attrs, defStyleAttr)
    }

    private lateinit var model: MediaEditorModel

    fun bindViewModel(model: MediaEditorModel, binder: Binder) {
        this.model = model
        controls.player.bindViewModel(model, binder)
        controls.controller.bindViewModel(model.playerControllerModel, binder)
        controls.volumePanel.bindViewModel(model.playerControllerModel, binder)
        controls.editorController.bindViewModel(model, binder)

        val showVolumePanel = MutableStateFlow(false)
        binder
            .visibilityBinding(controls.controller, model.playerControllerModel.showControlPanel, hiddenMode = VisibilityBinding.HiddenMode.HideByGone)
            .multiVisibilityBinding(arrayOf(controls.volumePanel,controls.volumeGuardView), showVolumePanel, hiddenMode = VisibilityBinding.HiddenMode.HideByGone)
            .clickBinding(controls.volumeGuardView) {
                showVolumePanel.value = false
            }
            .bindCommand(model.playerControllerModel.commandVolume) {
                if (model.playerModel.currentSource.value?.isPhoto != true) {
                    showVolumePanel.value = true
                }
            }
    }

    fun associatePlayer() {
        controls.player.associatePlayer()
    }
    fun dissociatePlayer() {
        controls.player.dissociatePlayer()
    }

    val manipulationTarget: IUtManipulationTarget
        get() = controls.player.manipulationTarget // SimpleManipulationTarget(controls.root, controls.player) // if(model.playerModel.isPhotoViewerEnabled) ExtendedManipulationTarget() else SimpleManipulationTarget(controls.root, controls.photoView)
}