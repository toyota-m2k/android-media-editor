package io.github.toyota32k.media.editor.project

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.editor.MainActivity.MediaSource.Companion.getType
import io.github.toyota32k.media.lib.io.AndroidFile
import io.github.toyota32k.media.lib.io.toAndroidFile
import io.github.toyota32k.utils.UtLazyResetableValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectDB(val application: Application, val dbFileName:String="AME.db") : AutoCloseable {
    val logger = UtLog(dbFileName)
    private var mDbInstance = UtLazyResetableValue<Database> {
         Room.databaseBuilder(application, Database::class.java, dbFileName).build()
    }

    val db:Database
        get() = mDbInstance.value

    override fun close() {
        mDbInstance.reset { it.close() }
    }

    fun <T> closingBlock(fn:()->T):T {
        try {
            return fn()
        } finally {
            close()
        }
    }

    fun safeGetDocumentId(uri: Uri):String {
        return try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Throwable) {
            uri.toString()
        }
    }

//    fun register(project:Project) {
//        db.projectTable().insert(project)
//    }

    fun isRegistered(documentId:String?):Boolean {
        if (documentId == null) return false
        return db.projectTable().get(documentId) != null
    }
    fun isRegistered(uri: Uri): Boolean {
        return isRegistered(safeGetDocumentId(uri))
    }

    fun normalizeUriForComparison(uri: Uri): Uri {
        // Uri のビルダーを作成し、全てのクエリパラメータを削除して、新しい Uri を構築する
        return uri.buildUpon()
            .clearQuery()
            .build()
    }

    fun isUriPersisted(targetUri: Uri): Boolean {
        val normalizedUri = normalizeUriForComparison(targetUri)
        // UriPermissionリストの中に、targetUriまたは、その正規化されたUriが含まれているかチェックする
        val persistedPermissions = application.contentResolver.persistedUriPermissions
        return if (targetUri == normalizedUri) {
            persistedPermissions.find { it.uri == targetUri } != null
        } else {
            persistedPermissions.find { it.uri == targetUri || it.uri == normalizedUri } != null
        }
    }

    private fun persistPermission(uri:Uri):Boolean {
        try {
            application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            logger.warn("cannot take read/write permission for $uri")
            try {
                application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                logger.error("cannot take read permission for $uri")
                return false
            }
        }
        return true
    }

    fun timestamp(uri:Uri):Long? {
        try {
            return if (uri.scheme == "file") {
                val path = uri.path ?: return null
                File(path).lastModified()
            } else {
                val docFile = DocumentFile.fromSingleUri(application, uri)
                docFile?.lastModified()
            }
        } catch (e:Throwable) {
            logger.error(e)
            return null
        }
    }

    fun registerProject(
        name:String,
        uri: String,
        type: String,
        serializedChapters: String?,
        serializedCropParams: String?,
        resolution: Int,
        ):Project? {
        var uriObj = uri.toUri()
        var saveUri = uri
        var documentId = safeGetDocumentId(uriObj)
        val now = Date().time
        var timestamp = timestamp(uriObj)?:now
        val oldProject = db.projectTable().get(documentId)
        logger.debug("saving")
        return if (oldProject == null) {
            var copied = false
            if (!persistPermission(uriObj)) {
                logger.info("no permission is persisted for the document: $documentId")
                val src = uriObj.toAndroidFile(application)
                val type = src.getType()
                if (type==null) {
                    logger.info("cannot register the document: file of unknown type")
                    return null
                }
                val dst = File(application.filesDir, "${SimpleDateFormat("yyyy.MM.dd-HH:mm:ss", Locale.US).format(Date())}.${type}").toAndroidFile()
                dst.copyFrom(src)
                copied = true
                saveUri = dst.safeUri.toString()
                documentId = saveUri
                uriObj = saveUri.toUri()
                if (timestamp==0L) {
                    timestamp = timestamp(uriObj) ?: now
                }
            }
            logger.debug("permission persisted for new document: $documentId")
            Project(0, name, documentId, type.lowercase(), saveUri, copied, serializedChapters, serializedCropParams, resolution,timestamp, now)
                .also {
                    db.projectTable().insert(it)
                    logger.debug("inserted: $documentId ${it.uri}")
                }
            // id を含む登録済みProjectインスタンスを返す
            getProject(uriObj)
        } else {
            oldProject.modified(name, saveUri, serializedChapters, serializedCropParams, resolution)
                ?.also {
                    db.projectTable().update(it)
                    logger.debug("updated: $documentId ${it.uri}")
                }
                ?.apply {
                    logger.debug("not updated: $documentId ${this.uri}")
                } ?: oldProject
        }
    }

    fun unregisterProject(project:Project) {
        if (project.copied) {
            logger.assertStrongly(project.uri.startsWith("file:"))
            runCatching { File(project.uri.toUri().path!!).delete() }
        } else {
            try {
                application.contentResolver.releasePersistableUriPermission(project.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch(e:Throwable) {
                logger.error(e)
            }
        }
        db.projectTable().delete(project)
    }

    fun getProject(uri:Uri) : Project? {
        val documentId = safeGetDocumentId(uri)
        return db.projectTable().get(documentId)
    }
    fun getProject(id:Int) : Project? {
        return db.projectTable().get(id)
    }

    fun getProjectList():List<Project> {
        return db.projectTable().getAll()
    }

    fun checkPoint() {
        db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(full);");
    }
//    companion object {
//        val Uri.documentId:String? get() = DocumentsContract.getDocumentId(this)
//    }
}

