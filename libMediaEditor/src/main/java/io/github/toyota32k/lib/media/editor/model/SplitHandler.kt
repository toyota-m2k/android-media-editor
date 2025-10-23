package io.github.toyota32k.lib.media.editor.model

import io.github.toyota32k.lib.player.model.IMediaSource
import io.github.toyota32k.lib.player.model.IPlayerModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

abstract class AbstractSplitHandler(val playerModel: IPlayerModel, val supportSplitting:Boolean) : ISplitHandler {
    override val splittable = if (supportSplitting) playerModel.currentSource.map { it?.type?.compareTo("mp4", ignoreCase = true) == 0 } else MutableStateFlow(false)
}

object NoopSplitHandler : ISplitHandler {
    override val splittable: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun splitVideoAt(targetSource: IMediaSource, positionMs: Long) {}
}


