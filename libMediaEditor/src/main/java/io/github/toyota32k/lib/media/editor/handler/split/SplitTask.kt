package io.github.toyota32k.lib.media.editor.handler.split

import io.github.toyota32k.lib.media.editor.handler.save.AbstractProgressSaveFileTask
import io.github.toyota32k.media.lib.converter.IOutputFileSelector

open class SplitTask(override val convertIfNeed: Boolean, override val fileSelector: IOutputFileSelector) : AbstractProgressSaveFileTask(), ISplitTask {
}