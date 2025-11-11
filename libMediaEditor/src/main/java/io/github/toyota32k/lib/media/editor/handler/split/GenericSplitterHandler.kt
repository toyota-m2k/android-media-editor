package io.github.toyota32k.lib.media.editor.handler.split

import io.github.toyota32k.lib.media.editor.model.ISplitHandler
import io.github.toyota32k.lib.media.editor.model.IVideoSourceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow


abstract class AbstractSplitHandler(supportSplitting:Boolean) : ISplitHandler {
    override val showSplitButton = MutableStateFlow(supportSplitting)
}

object NoopSplitHandler : ISplitHandler {
    override val showSplitButton: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun splitVideo(sourceInfo: IVideoSourceInfo):Boolean { return false }
}

class ChopSplitHandler(supportSplitting: Boolean):AbstractSplitHandler(supportSplitting) {
    override suspend fun splitVideo(sourceInfo: IVideoSourceInfo): Boolean {
        TODO("Not yet implemented")
    }
}
//class ChapterSplitHandler(supportSplitting: Boolean):AbstractSplitHandler(supportSplitting {
//
//}
