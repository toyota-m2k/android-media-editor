package io.github.toyota32k.lib.media.editor.handler

import android.content.Context
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.IOutputFileProvider
import io.github.toyota32k.media.lib.converter.AndroidFile
import io.github.toyota32k.media.lib.converter.toAndroidFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

class WorkFileMediator(val applicationContext: Context, val outputFileProvider: IOutputFileProvider, val inFile: AndroidFile) : AutoCloseable {
    private val logger = AmeGlobal.logger
    private var workFile: AndroidFile? = null
    private var outFile: AndroidFile? = null

    private fun ensureWorkFile():AndroidFile {
        return workFile ?: FileUtil.createWorkFile().apply {
            workFile = this
        }
    }

    private suspend fun ensureOutputFile():AndroidFile {
        return outFile ?: outputFileProvider.getOutputFile("video/mp4", inFile)?.apply {
            outFile = this
        } ?: throw CancellationException("cancelled")
    }

    private var isFinalized: Boolean = false

    /**
     * 1st stage (convert) は必ず作業ファイルに書き込む（途中でキャンセルされたときに元データを壊さないため）
     */
    suspend fun <R> firstStage(fn:suspend (inFile: AndroidFile, outFile: AndroidFile)->R):R {
        ensureOutputFile()
        val dstFile = ensureWorkFile()
        return fn(inFile, dstFile)
    }

    /**
     * last stage (optimize) は（途中キャンセルがないので）outFile に書き込む。
     */
    suspend fun <R> lastStage(fn:suspend (inFile: AndroidFile, outFile: AndroidFile)->R):R {
        val srcFile = ensureWorkFile()
        val dstFile = ensureOutputFile()
        isFinalized = true
        return fn(srcFile, dstFile)
    }


    suspend fun finalize():AndroidFile? {
        if (!isFinalized) {
            // last stage を実行していない場合は、作業ファイルを outFile にコピーする。
            val dst = outFile
            val src = workFile
            if (dst!=null && src!=null) {
                withContext(Dispatchers.IO) {
                    dst.copyFrom(src)
                }
            } else return null
        }
        return outFile?.also {
            outFile = null
        }
    }

    override fun close() {
        outFile?.safeDelete()
        workFile?.safeDelete()
    }
}