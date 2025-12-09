package io.github.toyota32k.media.editor.providers

import io.github.toyota32k.lib.media.editor.handler.split.ExportToDirectoryFileSelector
import io.github.toyota32k.lib.media.editor.handler.split.OneByOneExportFileSelector
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat

open class CustomOneByOneExportFileSelector(val projectName: StateFlow<String>, prefix:String="mov-", dateFormat: DateFormat?=null): OneByOneExportFileSelector(prefix, dateFormat) {
    override fun getBaseName(): String {
        return projectName.value.takeIf { it.isNotBlank() } ?: super.getBaseName()
    }
}
class CustomExportToDirectoryFileSelector(val projectName: StateFlow<String>, prefix: String="mov-", dateFormat: DateFormat?=null) : ExportToDirectoryFileSelector(prefix, dateFormat) {
    override fun getBaseName(): String {
        return projectName.value.takeIf { it.isNotBlank() } ?: super.getBaseName()
    }
}
