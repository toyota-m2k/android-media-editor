package io.github.toyota32k.lib.media.editor.output

import android.content.Context
import io.github.toyota32k.dialog.broker.IUtActivityBrokerStoreProvider
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.toAndroidFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

class WorkFileMediator(val applicationContext: Context, val inFile: AndroidFile, val overwrite:Boolean, val optimize:Boolean, private var outFile:AndroidFile?=null, private var outputFileSuffix:String?=null) : AutoCloseable {
    private val logger = AmeGlobal.logger
    private var workFile: AndroidFile? = null

    companion object {
        fun contentType2Ext(contentType: String): String {
            return when (contentType) {
                "image/png"->".png"
                "image/jpeg"->".jpg"
                "image/gif"->".gif"
                "video/mp4"->".mp4"
                else -> ""
            }
        }
        fun getExtension(file:AndroidFile):String {
            return contentType2Ext(file.getContentType()?:"")
        }

        fun createInitialFileName(originalName: String, suffix:String="(edited)", ext: String = ".mp4"): String {
            val dotIndex = originalName.lastIndexOf('.')
            val baseName = if (dotIndex > 0) originalName.take(dotIndex) else originalName
            return "${baseName}${suffix}$ext"
        }

        suspend fun selectFile(type: String, initialFileName: String): AndroidFile? {
            val owner = UtImmortalTaskManager.mortalInstanceSource.getOwner()
            val pickerProvider = owner.lifecycleOwner as? IUtActivityBrokerStoreProvider ?: owner.asActivity() as? IUtActivityBrokerStoreProvider ?: return null
            val filePicker = pickerProvider.activityBrokers.createFilePicker
            return filePicker.selectFile(initialFileName, type)?.toAndroidFile(owner.application)
        }
        suspend fun selectFile(type:String, srcFile:AndroidFile, outputFileSuffix:String="(edited)", ext:String=getExtension(srcFile)):AndroidFile? {
            val initialFileName = createInitialFileName(srcFile.getFileName()?:"unnamed", outputFileSuffix, ext)
            return selectFile(type, initialFileName)
        }

        val dateFormatForFilename = SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US)
        fun defaultFileNameSuffix(): String {
            return dateFormatForFilename.format(Date())
        }
    }

    private fun ensureWorkFile():AndroidFile {
        logger.assertStrongly(workFile==null, "workFile must be null")
        return File.createTempFile("ame", ".tmp", applicationContext.cacheDir).toAndroidFile().apply {
            workFile = this
        }
    }

    /**
     * コンバート前に保存ファイルを選択しておく場合はこれを呼ぶ
     * @throws CancellationException
     */
    suspend fun setupOutputFile() {
        ensureOutputFile()
    }


    private suspend fun ensureOutputFile():AndroidFile {
        val initialFileName = createInitialFileName(inFile.getFileName()?:"unnamed", outputFileSuffix?:defaultFileNameSuffix(), ".mp4")
        return outFile ?: selectFile("video/mp4", initialFileName)?.apply {
            outFile = this
        } ?: throw CancellationException("cancelled")
    }

    suspend fun <R> firstStage(fn:suspend (inFile: AndroidFile, outFile: AndroidFile)->R):R {
        val srcFile = inFile
        val dstFile = if (overwrite||optimize) {
            logger.assertStrongly(workFile==null, "workFile must be null")
            ensureWorkFile()
        } else {
            ensureOutputFile()
        }
        return fn(srcFile, dstFile)
    }

    suspend fun <R> lastStage(fn:suspend (inFile: AndroidFile, outFile: AndroidFile)->R):R {
        val srcFile = workFile ?: inFile
        val dstFile = if (overwrite) inFile else ensureOutputFile()
        return fn(srcFile, dstFile)
    }


    fun complete():AndroidFile? {
        val r = outFile
        outFile = null
        workFile?.safeDelete()
        return r
    }
    fun abort() {
        outFile?.safeDelete()
        workFile?.safeDelete()
    }

    override fun close() {
        abort()
    }
}