package io.github.toyota32k.media.editor.providers

import io.github.toyota32k.lib.media.editor.handler.ExportFileProvider
import io.github.toyota32k.lib.media.editor.handler.InteractiveOutputFileProvider
import io.github.toyota32k.lib.media.editor.handler.OverwriteFileProvider
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.media.lib.io.AndroidFile
import kotlinx.coroutines.flow.StateFlow

class CustomExportFileProvider(val projectName: StateFlow<String>, outputFileSuffix: String="") : ExportFileProvider(outputFileSuffix) {
    override suspend fun getBaseFileName(inputFile: AndroidFile): String {
        return projectName.value.takeIf { it.isNotBlank() } ?: super.getBaseFileName(inputFile)
    }
}

class CustomOverwriteFileProvider(val projectName: StateFlow<String>, showConfirmMessage:Boolean=true, workSubFolder:String?=null) : OverwriteFileProvider(showConfirmMessage, workSubFolder) {
    override suspend fun getFallbackProvider(): IOutputFileProvider {
        return CustomExportFileProvider(projectName)
    }
}

class CustomInteractiveOutputFileProvider(val projectName: StateFlow<String>, outputFileSuffix:String="", subFolder:String?=null): InteractiveOutputFileProvider(outputFileSuffix, subFolder) {
    override suspend fun getBaseFileName(inputFile: AndroidFile): String {
        return projectName.value.takeIf { it.isNotBlank() } ?: super.getBaseFileName(inputFile)
    }

    override fun getExportFileProvider(): IOutputFileProvider {
        return CustomExportFileProvider(projectName)
    }

    override fun getOverwriteFileProvider(): IOutputFileProvider {
        return CustomOverwriteFileProvider(projectName, true, null)
    }
}