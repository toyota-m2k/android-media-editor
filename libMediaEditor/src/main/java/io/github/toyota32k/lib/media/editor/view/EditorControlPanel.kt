package io.github.toyota32k.lib.media.editor.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.BoolConvert
import io.github.toyota32k.binder.VisibilityBinding
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.combinatorialVisibilityBinding
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.enableBinding
import io.github.toyota32k.binder.multiVisibilityBinding
import io.github.toyota32k.binder.observe
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.lib.media.editor.R
import io.github.toyota32k.lib.media.editor.databinding.EditorControlPanelBinding
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.EditorPlayerViewAttributes
import io.github.toyota32k.lib.media.editor.model.MediaEditorModel
import io.github.toyota32k.lib.player.view.ControlPanel.Companion.createButtonColorStateList
import io.github.toyota32k.utils.android.StyledAttrRetriever
import io.github.toyota32k.utils.android.lifecycleOwner
import io.github.toyota32k.utils.lifecycle.asConstantLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.sequences.forEach
import kotlin.use

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
                resolutionButtonsPanel.children.forEach { (it as? ImageButton)?.imageTintList = buttonTint }
                cropButtonsPanel.children.forEach { (it as? ImageButton)?.imageTintList = buttonTint }
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
            .visibilityBinding(controls.magnifyTimeline, model.supportMagnifyingTimeline.asConstantLiveData(), BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .visibilityBinding(controls.cropVideo, model.cropHandler.croppable, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .visibilityBinding(controls.chopVideo, model.splitHandler.splittable, BoolConvert.Straight, VisibilityBinding.HiddenMode.HideByGone)
            .observe(model.editMode) {
                when(it) {
                    MediaEditorModel.EditMode.NONE-> {
                        controls.editorMainButtonPanel.visibility = View.VISIBLE
                        controls.cropButtonsPanel.visibility = View.GONE
                        controls.resolutionButtonsPanel.visibility = View.GONE
                    }
                    MediaEditorModel.EditMode.CROPPING-> {
                        controls.editorMainButtonPanel.visibility = View.GONE
                        controls.cropButtonsPanel.visibility = View.VISIBLE
                        controls.resolutionButtonsPanel.visibility = View.GONE
                    }
                    MediaEditorModel.EditMode.RESOLUTION_CHANGING-> {
                        controls.editorMainButtonPanel.visibility = View.GONE
                        controls.cropButtonsPanel.visibility = View.GONE
                        controls.resolutionButtonsPanel.visibility = View.VISIBLE
                    }
                }
            }
            .enableBinding(controls.undoChapter, model.chapterEditorHandler.canUndo)
            .enableBinding(controls.redoChapter, model.chapterEditorHandler.canRedo)

            .bindCommand(model.chapterEditorHandler.commandAddChapter, controls.makeChapter)
            .bindCommand(model.chapterEditorHandler.commandAddSkippedChapterBefore, controls.makeChapterAndSkip)
            .bindCommand(model.chapterEditorHandler.commandToggleSkipChapter, controls.makeRegionSkip)
            .bindCommand(model.chapterEditorHandler.commandRemoveChapterAfter, controls.removeNextChapter)
            .bindCommand(model.chapterEditorHandler.commandRemoveChapterBefore, controls.removePrevChapter)
            .bindCommand(model.chapterEditorHandler.commandUndoChapter, controls.undoChapter)
            .bindCommand(model.chapterEditorHandler.commandRedoChapter, controls.redoChapter)

            .bindCommand(model.cropHandler.commandBeginCrop, controls.cropVideo)
            .bindCommand(model.cropHandler.commandResetCrop, controls.cropResetButton)
            .bindCommand(model.cropHandler.commandCancelCrop, controls.cropCancelButton)
            .bindCommand(model.cropHandler.commandCompleteCrop, controls.cropCompleteButton)
            .bindCommand(model.cropHandler.commandSetCropToMemory, controls.cropStoreToMemory)
            .bindCommand(model.cropHandler.commandRestoreCropFromMemory, controls.cropRestoreFromMemory)

            .bindCommand(model.cropHandler.commandStartResolutionChanging, controls.resolutionButton)
            .bindCommand(model.cropHandler.commandCompleteResolutionChanging, controls.acceptResolutionButton)
            .bindCommand(model.cropHandler.commandCancelResolutionChanging, controls.cancelResolutionButton)

            .clickBinding(controls.chopVideo) {
            }
            .clickBinding(controls.saveVideo) {
                model.saveFile()
            }












    }
}