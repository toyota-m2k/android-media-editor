package io.github.toyota32k.media.editor.project

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.room.Room
import io.github.toyota32k.utils.UtLazyResetableValue
import java.util.Date

class ProjectDB(val application: Application, val dbFileName:String="AME.db") : AutoCloseable {
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


    fun register(project:Project) {
        db.projectTable().insert(project)
    }

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

    fun register(
        name:String,
        uri: String,
        type: String,
        serializedChapters: String?,
        serializedCropParams: String?):Project? {
        val uriObj = uri.toUri()
        val documentId = DocumentsContract.getDocumentId(uriObj) ?: return null
        val persisted = isUriPersisted(uriObj)
        val date = Date()
        val oldProject = db.projectTable().get(documentId)
        return if (oldProject == null) {
            Project(0, name, documentId, type, if (persisted) uri else null, serializedChapters, serializedCropParams, date.time, date.time)
                .also { db.projectTable().insert(it) }
        } else {
            oldProject.modified(uri, serializedChapters, serializedCropParams)
                ?.also { db.projectTable().update(it) }
        }
    }

    fun getProject(uri:Uri) : Project? {
        val documentId = DocumentsContract.getDocumentId(uri) ?: return null
        return db.projectTable().get(documentId)
    }

    fun checkPoint() {
        db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(full);");
    }
}

