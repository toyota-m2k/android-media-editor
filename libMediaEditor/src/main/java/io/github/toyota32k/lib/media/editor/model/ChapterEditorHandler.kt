package io.github.toyota32k.lib.media.editor.model

import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.lib.player.model.IChapter
import io.github.toyota32k.lib.player.model.IMediaSourceWithChapter
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.lib.player.model.chapter.ChapterEditor
import io.github.toyota32k.lib.player.model.chapter.MutableChapterList
import io.github.toyota32k.lib.player.model.skipChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * IChapterEditorHandler の実装クラス
 */
open class ChapterEditorHandler(protected val playerModel: IPlayerModel, supportChapterEditing:Boolean)
    : IChapterEditorHandler {
    init {
        val scope = CoroutineScope(playerModel.scope.coroutineContext + SupervisorJob())
        playerModel.currentSource.onEach {
            if (it is IMediaSourceWithChapter) {
                setChapterList(it.getChapterList().chapters)
            }
        }.launchIn(scope)
    }
    protected var chapterEditor = ChapterEditor(MutableChapterList())
    override val chapterEditable = if(supportChapterEditing) playerModel.currentSource.map { it?.type?.compareTo("mp4", ignoreCase = true) == 0 } else MutableStateFlow(false)
    override val commandAddChapter: IUnitCommand = LiteUnitCommand(::onAddChapter)
    override val commandAddSkippedChapterBefore: IUnitCommand = LiteUnitCommand(::onAddSkippedChapterBefore)
    override val commandToggleSkipChapter: IUnitCommand = LiteUnitCommand(::onToggleSkipChapter)
    override val commandRemoveChapterBefore: IUnitCommand = LiteUnitCommand(::onRemoveChapterBefore)
    override val commandRemoveChapterAfter: IUnitCommand = LiteUnitCommand(::onRemoveChapterAfter)
    override val commandUndoChapter: IUnitCommand = LiteUnitCommand(::onUndoChapter)
    override val commandRedoChapter: IUnitCommand = LiteUnitCommand(::onRedoChapter)
    override val chapterListModified: Flow<Boolean> = combine(chapterEditable, chapterEditor.canUndo) {editable, _-> editable && chapterEditor.isDirty}

    open fun onAddChapter() {
        chapterEditor.addChapter(playerModel.currentPosition, "", null)
    }
    open fun onAddSkippedChapterBefore() {
        val neighbor = chapterEditor.getNeighborChapters(playerModel.currentPosition)
        val prev = neighbor.getPrevChapter(chapterEditor)
        if(neighbor.hit<0) {
            // 現在位置にチャプターがなければ追加する
            if(!chapterEditor.addChapter(playerModel.currentPosition, "", null)) {
                return
            }
        }
        // ひとつ前のチャプターを無効化する
        if(prev!=null) {
            chapterEditor.skipChapter(prev, true)
        }
    }
    open fun onToggleSkipChapter() {
        val chapter = chapterEditor.getChapterAround(playerModel.currentPosition) ?: return
        chapterEditor.skipChapter(chapter, !chapter.skip)
    }
    open fun onRemoveChapterBefore() {
        val neighbor = chapterEditor.getNeighborChapters(playerModel.currentPosition)
        chapterEditor.removeChapterAt(neighbor.prev)
    }
    open fun onRemoveChapterAfter() {
        val neighbor = chapterEditor.getNeighborChapters(playerModel.currentPosition)
        chapterEditor.removeChapterAt(neighbor.next)
    }
    open fun onUndoChapter() {
        chapterEditor.undo()
    }
    open fun onRedoChapter() {
        chapterEditor.redo()
    }

    override val canRedo: Flow<Boolean>
        get() = chapterEditor.canRedo
    override val canUndo: Flow<Boolean>
        get() = chapterEditor.canUndo


    // region ChapterEditor の初期化

    fun setChapterList(list: List<IChapter>) {
        this.chapterEditor.initChapters(list)
    }

    // endregion

    override fun getEnabledRangeList(): List<Range> {
        return chapterEditor.enabledRanges(Range.empty)
    }
}