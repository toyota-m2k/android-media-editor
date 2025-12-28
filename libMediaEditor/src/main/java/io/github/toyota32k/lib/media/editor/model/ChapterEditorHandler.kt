package io.github.toyota32k.lib.media.editor.model

import io.github.toyota32k.binder.command.IUnitCommand
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.lib.media.editor.model.AmeGlobal.logger
import io.github.toyota32k.lib.player.model.IChapter
import io.github.toyota32k.lib.player.model.IChapterList
import io.github.toyota32k.lib.player.model.IMutableChapterList
import io.github.toyota32k.lib.player.model.IPlayerModel
import io.github.toyota32k.lib.player.model.Range
import io.github.toyota32k.lib.player.model.chapter.ChapterEditor
import io.github.toyota32k.lib.player.model.chapter.MutableChapterList
import io.github.toyota32k.lib.player.model.skipChapter
import io.github.toyota32k.media.lib.processor.contract.IActualSoughtMap
import io.github.toyota32k.media.lib.processor.contract.ISoughtMap
import io.github.toyota32k.media.lib.types.RangeUs.Companion.formatAsMs
import io.github.toyota32k.media.lib.types.RangeUs.Companion.formatAsUs
import io.github.toyota32k.media.lib.types.RangeUs.Companion.ms2us
import io.github.toyota32k.media.lib.types.RangeUs.Companion.us2ms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * IChapterEditorHandler の実装クラス
 */
open class ChapterEditorHandler(protected val playerModel: IPlayerModel, supportChapterEditing:Boolean)
    : IChapterEditorHandler {
    init {
        val scope = CoroutineScope(playerModel.scope.coroutineContext + SupervisorJob())
        playerModel.currentSource.onEach {
            if (it is IMediaSourceWithMutableChapterList) {
                setChapterList(it.getChapterList())
            }
        }.launchIn(scope)
    }
    protected val chapterEditor = ChapterEditor()
    override val chapterEditable = if(supportChapterEditing) playerModel.isCurrentSourceVideo else MutableStateFlow(false)
    override val commandAddChapter: IUnitCommand = LiteUnitCommand(::onAddChapter)
    override val commandAddSkippedChapterBefore: IUnitCommand = LiteUnitCommand(::onAddSkippedChapterBefore)
    override val commandToggleSkipChapter: IUnitCommand = LiteUnitCommand(::onToggleSkipChapter)
    override val commandRemoveChapterBefore: IUnitCommand = LiteUnitCommand(::onRemoveChapterBefore)
    override val commandRemoveChapterAfter: IUnitCommand = LiteUnitCommand(::onRemoveChapterAfter)
    override val commandUndoChapter: IUnitCommand = LiteUnitCommand(::onUndoChapter)
    override val commandRedoChapter: IUnitCommand = LiteUnitCommand(::onRedoChapter)
    override val chapterListModified: Flow<Boolean> = combine(chapterEditable, chapterEditor.canUndo) {editable, _-> editable && chapterEditor.isDirty}
    override val isDirty:Boolean get() = chapterEditable.value && chapterEditor.isDirty

    override fun clearDirty() {
        chapterEditor.clearDirty()
    }

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

    private fun setChapterList(list: IMutableChapterList) {
        this.chapterEditor.setTarget(list)
    }

    // endregion

    override fun getEnabledRangeList(): List<Range> {
        return chapterEditor.enabledRanges(Range.empty)
    }

    override fun getChapterList(): IChapterList {
        return chapterEditor
    }

    /**
     * actualSoughtMap にしたがって、編集中の chapterList を補正する。
     */
    override fun correctChapterList(soughtMap: ISoughtMap): List<IChapter> {
        return correctChapterList(soughtMap, chapterEditor)
    }

    companion object {
        fun correctChapterList(soughtMap: ISoughtMap, chapterList: IChapterList): List<IChapter> {
            val newList = MutableChapterList()
            // ...|...skipped...|...enabled...|...
            //    a             b             c
            // のような chapter 構成の場合 aとb は結果的に同じ位置を指すことになるが、
            // スキップされる a より、有効なチャプターである、b を優先的に採用した。
            // MutableChapterList は一定範囲内に、２つ以上のチャプターを登録するのを禁止しているので、
            // 近接するチャプターを追加する場合は、最初の登録が有効となる。b を a より先に登録するため、
            // chapterリストを後ろから順に列挙して処理する。
            for (c in chapterList.chapters.asReversed()) {
                val pos = soughtMap.correctPositionUs(c.position.ms2us())
                if (pos<0) continue
//                logger.debug("correct with soughtMap: ${c.position.formatAsMs()} -> ${pos.formatAsUs()}")
                newList.addChapter(pos.us2ms(), c.label, false)
            }
            return newList.chapters
        }
    }


        /**
         * actualSoughtMap にしたがって、与えられた chapterList を補正する。
         */
//        fun correctChapterList(actualSoughtMap: IActualSoughtMap, chapterList: IChapterList): List<IChapter> {
//            val list = actualSoughtMap.entries.sortedBy { it.key }
//
//            fun offsetBySoughtMap(posMs: Long): Long {
//                val posUs = posMs.ms2us()
//                var ofs = 0L
//                for (c in list) {
//                    if (posUs < c.key) {
//                        break
//                    }
//                    ofs = c.value - c.key
//                }
//                return ofs /1000L
//            }
//
//            fun correctPos(posMs: Long): Long {
//                if (posMs<=0||posMs==Long.MAX_VALUE) return Long.MAX_VALUE
//                return posMs - offsetBySoughtMap(posMs)
//            }
//
//            val outlineRangeMs = actualSoughtMap.outlineRangeUs.toRangeMs()
//            val originalEnabledRanges = chapterList.enabledRanges(Range.empty)
//            fun isValidOrgPosition(posMs: Long): Boolean {
//                if (posMs < outlineRangeMs.startMs || outlineRangeMs.endMs < posMs) return false
//                return originalEnabledRanges.firstOrNull { it.start <= posMs && posMs <= it.end } != null
//            }
//
//            val adjustedEnabledRanges = originalEnabledRanges.map { Range(correctPos(it.start), correctPos(it.end)) }
//            fun adjustPos(correctedPosMs: Long): Long {
//                var prev: Range? = null
//                var ofs = outlineRangeMs.startMs
//                for (r in adjustedEnabledRanges) {
//                    if (prev != null) {
//                        ofs += r.start - prev.end
//                    }
//                    if (r.start <= correctedPosMs && correctedPosMs <= r.end) {
//                        return correctedPosMs - ofs
//                    }
//                    prev = r
//                }
//                return -1
//            }
//
//            val newChapterList = MutableChapterList()
//            for (c in chapterList.chapters) {
//                if (isValidOrgPosition(c.position)) {
//                    val pos = adjustPos(correctPos(c.position))
//                    newChapterList.addChapter(pos, c.label, false)
//                }
//            }
//            return newChapterList.chapters
//        }
//
//    }
}