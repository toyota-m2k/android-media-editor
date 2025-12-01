package io.github.toyota32k.media.editor.project

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.core.os.persistableBundleOf
import androidx.room.Room
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.utils.UtLazyResetableValue
import java.util.Date

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


//    fun register(project:Project) {
//        db.projectTable().insert(project)
//    }

    fun isRegistered(documentId:String?):Boolean {
        if (documentId == null) return false
        return db.projectTable().get(documentId) != null
    }
    fun isRegistered(uri: Uri): Boolean {
        return isRegistered(DocumentsContract.getDocumentId(uri))
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
            try {
                application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                return false
            }
        }
        return true
    }

    fun register(
        name:String,
        uri: String,
        type: String,
        serializedChapters: String?,
        serializedCropParams: String?):Project? {
        val uriObj = uri.toUri()
        val documentId = DocumentsContract.getDocumentId(uriObj) ?: return null
        val date = Date()
        val oldProject = db.projectTable().get(documentId)
        logger.debug("saving")
        return if (oldProject == null) {
            persistPermission(uriObj)
            logger.debug("permission persisted for new document: $documentId")
            Project(0, name, documentId, type, uri, serializedChapters, serializedCropParams, date.time, date.time)
                .also {
                    db.projectTable().insert(it)
                    logger.debug("inserted: $documentId ${it.uri}")
                }
        } else {
            oldProject.modified(name, uri, serializedChapters, serializedCropParams)
                ?.also {
                    db.projectTable().update(it)
                    logger.debug("updated: $documentId ${it.uri}")
                }
                ?.apply {
                    logger.debug("not updated: $documentId ${this.uri}")
                }
        }
    }

    fun unregister(project:Project) {
        application.contentResolver.releasePersistableUriPermission(project.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        db.projectTable().delete(project)
    }

    fun getProject(uri:Uri) : Project? {
        val documentId = DocumentsContract.getDocumentId(uri) ?: return null
        return db.projectTable().get(documentId)
    }

    fun checkPoint() {
        db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(full);");
    }
    companion object {
        val Uri.documentId:String? get() = DocumentsContract.getDocumentId(this)
    }
}

