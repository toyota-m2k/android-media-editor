package io.github.toyota32k.lib.media.editor.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.BoolConvert
import io.github.toyota32k.binder.VisibilityBinding
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.enableBinding
import io.github.toyota32k.binder.multiVisibilityBinding
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.lib.media.editor.R
import io.github.toyota32k.lib.media.editor.databinding.EditorControlPanelBinding
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.AspectMode
import io.github.toyota32k.lib.media.editor.model.EditorPlayerViewAttributes
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.lib.media.editor.output.ExportFileProvider
import io.github.toyota32k.lib.player.view.ControlPanel.Companion.createButtonColorStateList
import io.github.toyota32k.utils.android.lifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EditorControlPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : FrameLayout(context, attrs, defStyleAttr) {
    val logger = AmeGlobal.logger
    val controls = EditorControlPanelBinding.inflate(LayoutInflater.from(context), this, true)

    fun setControlPanelAttributes(epa: EditorPlayerViewAttributes) {
        if (epa.sarForPlayer.sa.getBoolean(io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampAttrsByParent, true)) {
            val panelBackground = epa.sarForPlayer.getDrawableWithAlphaOnFallback(
                io.github.toyota32k.lib.player.R.styleable.ControlPanel_ampPanelBackgroundColor,
                com.google.android.material.R.attr.colorSurface,
                def = Color.WHITE, alpha = 0x50
            )

            val buttonTint = createButtonColorStateList(epa.sarForPlayer)
            val padding = epa.sarForEditor.sa.getDimensionPixelSize(R.styleable.MediaEditor_amePanelPadding, 0)
            val paddingStart = epa.sarForEditor.sa.getDimensionPixelSize(R.styleable.MediaEditor_amePanelPaddingStart, padding)
            val paddingTop = epa.sarForEditor.sa.getDimensionPixelSize(R.styleable.MediaEditor_amePanelPaddingTop, padding)
            val paddingEnd = epa.sarForEditor.sa.getDimensionPixelSize(R.styleable.MediaEditor_amePanelPaddingEnd, padding)
            val paddingBottom = epa.sarForEditor.sa.getDimensionPixelSize(R.styleable.MediaEditor_amePanelPaddingBottom, padding)

            controls.apply {
                root.background = panelBackground
                root.setPadding(paddingStart, paddingTop, paddingEnd, paddingBottom)
                editorMainButtonPanel.children.forEach { (it as? ImageButton)?.imageTintList = buttonTint }
                cropPanel.children.forEach {
                    when (it) {
                        is ImageButton -> it.imageTintList = buttonTint
                        is Button -> it.setTextColor(buttonTint)
                    }
                }
                resolutionButtons.children.forEach { (it as? ImageButton)?.imageTintList = buttonTint }
            }
        }
    }


    init {
        EditorPlayerViewAttributes(context, attrs, defStyleAttr).use { epa ->
            setControlPanelAttributes(epa)
        }
    }

    fun bindViewModel(model: MediaEditorModel, binder: Binder) {
        model.cropHandler.bindView(binder, controls.resolutionSlider, controls.buttonMinus, controls.buttonPlus, mapOf(480 to controls.button480, 720 to controls.button720, 1280 to controls.button1280, 1920 to controls.button1920))
        binder
            .multiVisibilityBinding(arrayOf(controls.makeChapter, controls.makeRegionSkip, controls.undoChapter, controls.redoChapter, controls.makeChapterAndSkip, controls.removeNextChapter, controls.removePrevChapter), model.chapterEditorHandler.chapterEditable, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .visibilityBinding(controls.cropVideo, model.cropHandler.croppable, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .visibilityBinding(controls.chopVideo, combine(model.splitHandler.showSplitButton, model.playerModel.isCurrentSourceVideo) {show,video-> show && video }, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .visibilityBinding(controls.saveVideo, model.saveFileHandler.showSaveButton, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .multiVisibilityBinding(arrayOf(controls.cropCancelButton, controls.cropCompleteButton), model.cropHandler.showCompleteCancelButton, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .visibilityBinding(controls.editorMainButtonPanel, model.editMode.map { it == MediaEditorModel.EditMode.NORMAL})
            .visibilityBinding(controls.cropPanel, model.editMode.map { it == MediaEditorModel.EditMode.CROP})
            .visibilityBinding(controls.resolutionPanel, model.editMode.map { it == MediaEditorModel.EditMode.RESOLUTION})
            .visibilityBinding(controls.resolutionButton, model.playerModel.isCurrentSourcePhoto, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .visibilityBinding(controls.resolutionPanel, model.cropHandler.resolutionChangingNow, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .enableBinding(controls.undoChapter, model.chapterEditorHandler.canUndo)
            .enableBinding(controls.redoChapter, model.chapterEditorHandler.canRedo)

            .bindCommand(model.chapterEditorHandler.commandAddChapter, controls.makeChapter)
            .bindCommand(model.chapterEditorHandler.commandAddSkippedChapterBefore, controls.makeChapterAndSkip)
            .bindCommand(model.chapterEditorHandler.commandToggleSkipChapter, controls.makeRegionSkip)
            .bindCommand(model.chapterEditorHandler.commandRemoveChapterAfter, controls.removeNextChapter)
            .bindCommand(model.chapterEditorHandler.commandRemoveChapterBefore, controls.removePrevChapter)
            .bindCommand(model.chapterEditorHandler.commandUndoChapter, controls.undoChapter)
            .bindCommand(model.chapterEditorHandler.commandRedoChapter, controls.redoChapter)

            .textBinding(controls.aspectButton, model.cropHandler.cropAspectMode.map { it.label })
            .bindCommand(model.cropHandler.commandBeginCrop, controls.cropVideo)
            .bindCommand(model.cropHandler.commandResetCrop, controls.cropResetButton)
            .bindCommand(model.cropHandler.commandCancelCrop, controls.cropCancelButton)
            .bindCommand(model.cropHandler.commandCompleteCrop, controls.cropCompleteButton)
            .bindCommand(model.cropHandler.commandSetCropToMemory, controls.cropStoreToMemory)
            .bindCommand(model.cropHandler.commandRestoreCropFromMemory, controls.cropRestoreFromMemory)

            .bindCommand(model.cropHandler.commandBeginResolutionChanging, controls.resolutionButton)
            .bindCommand(model.cropHandler.commandCancelResolutionChanging, controls.resolutionCancelButton)
            .bindCommand(model.cropHandler.commandCompleteResolutionChanging, controls.resolutionCompleteButton)
            .bindCommand(model.cropHandler.commandResetResolution, controls.resolutionResetButton)

            .clickBinding(controls.aspectButton) {
                lifecycleOwner()?.lifecycleScope?.launch {
                    val aspect = popupAspectMenu(context, it)
                    if(aspect!=null) {
                        model.cropHandler.maskViewModel.aspectMode.value = aspect
                    }
                }
            }

            .clickBinding(controls.chopVideo) {
                model.playerModel.scope.launch {
                    model.splitVideo()
                }
            }
            .clickBinding(controls.saveVideo) {
                model.playerModel.scope.launch {
                    model.saveFile(ExportFileProvider("-(edited)"))
                }
            }
    }

    companion object  {
        suspend fun popupAspectMenu(context: Context, anchor: View): AspectMode? {
            val selection = MutableStateFlow<Int?>(null)
            PopupMenu(context, anchor).apply {
                setOnMenuItemClickListener {
                    selection.value = it.itemId
                    true
                }
                setOnDismissListener {
                    selection.value = -1
                }
                inflate(R.menu.menu_aspect)
            }.show()
            val sel = selection.first { it != null }
            return when(sel) {
                R.id.aspect_free -> AspectMode.FREE
                R.id.aspect_4_3 -> AspectMode.ASPECT_4_3
                R.id.aspect_16_9 -> AspectMode.ASPECT_16_9
                else -> null
            }
        }
    }
}