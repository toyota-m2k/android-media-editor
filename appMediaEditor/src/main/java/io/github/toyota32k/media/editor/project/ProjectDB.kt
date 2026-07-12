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
import io.github.toyota32k.media.lib.io.toAndroidFile
import io.github.toyota32k.utils.UtLazyResetableValue
import io.github.toyota32k.utils.android.IUtFile
import io.github.toyota32k.utils.android.UtFile
import io.github.toyota32k.utils.android.toUtFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
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

//    fun <T> closingBlock(fn:()->T):T {
//        try {
//            return fn()
//        } finally {
//            close()
//        }
//    }


//    fun register(project:Project) {
//        db.projectTable().insert(project)
//    }

    fun isRegistered(uri:String?):Boolean {
        if (uri.isNullOrEmpty()) return false
        return db.projectTable().getByUri(uri) != null
    }
    fun isRegistered(uri: Uri): Boolean {
        return isRegistered(uri.toString())
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
        sourceUri: Uri,
        type: String,
        serializedChapters: String?,
        serializedCropParams: String?,
        resolution: Int,
        hash: String? = null
        ):Project? {
        val now = Date().time
        val timestamp = timestamp(sourceUri) ?: now
        val hash = hash ?: sha1Of(sourceUri.toUtFile(application))
        val oldProject = db.projectTable().getByUri(sourceUri.toString())
        if (oldProject == null) {
            var copiedUri:String? = null
            if (!persistPermission(sourceUri)) {
                logger.info("no permission is persisted for : $sourceUri")
                val src = sourceUri.toAndroidFile(application)
                val type = src.getType()
                if (type==null) {
                    logger.info("unknown type: $sourceUri")
                    return null
                }
                val dst = File(application.filesDir, "${SimpleDateFormat("yyyy.MM.dd-HH:mm:ss", Locale.US).format(Date())}.${type}").toAndroidFile()
                dst.copyFrom(src)
                copiedUri = dst.safeUri.toString()
            }
            Project(0, name, type.lowercase(), sourceUri.toString(), copiedUri, serializedChapters, serializedCropParams, resolution,timestamp, now, hash)
                .also {
                    db.projectTable().insert(it)
                    logger.debug("inserted: ${it.sourceUri} ${it.name} copied=${it.copied}")
                }
            // id を含む登録済みProjectインスタンスを返す
            return getProject(sourceUri)
        } else {
            return (oldProject.modified(name, serializedChapters, serializedCropParams, resolution, hash) ?: return oldProject).also {
                updateProject(it)
            }
        }
    }

    /**
     * プロジェクトの可変データを更新
     * registerProject() での変更可能だが、こちらは hash計算がないぶん軽量
     */
    fun updateProjectVariables(
        project:Project, name:String,
        serializedChapters: String?,
        serializedCropParams: String?,
        resolution: Int,
    ) : Project {
        val newProject = project.modified(name, serializedChapters, serializedCropParams, resolution) ?: return project
        updateProject(newProject)
        return newProject
    }

    /**
     * アプリのデータ領域にコピーして編集している場合、プロジェクトを削除すると、変更が失われるのでチェックできるようにしておく。
     */
    fun isDirty(project:Project):Boolean {
        val copiedUri = project.copiedUri ?: return false   // 直接編集の場合は失われるファイルはない
        val hash = sha1Of(copiedUri.toUri().toUtFile())
        return hash != project.hash
    }

    fun updateProject(newProject: Project) {
        db.projectTable().update(newProject)
        logger.debug("updated: ${newProject.sourceUri} ${newProject.name}")
    }

    fun unregisterProject(project:Project) {
        if (project.copied) {
            // コピーされている場合は、ファイル削除
            logger.assertStrongly(project.editingUri.startsWith("file:"))
            val path = project.copiedUri?.toUri()?.path
            if (path != null) {
                runCatching { File(path).delete() }
            }
        } else {
            try {
                application.contentResolver.releasePersistableUriPermission(project.sourceUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch(e:Throwable) {
                logger.error(e)
            }
        }
        db.projectTable().delete(project)
    }

//    fun getProject(hash:String) : Project? {
//        return db.projectTable().getByHash(hash)
//    }
    fun getProject(uri:Uri) : Project? {
        return db.projectTable().getByUri(uri.toString())
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

    suspend fun sha1Of(uri:Uri):String {
        return withContext(Dispatchers.IO) { sha1Of(uri.toUtFile(application)) }
    }
    companion object {
        fun sha1Of(inputStream: FileInputStream): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val buffer = ByteArray(8 * 1024)
            inputStream.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun sha1Of(file:IUtFile): String {
            return file.fileInputStream {
                sha1Of(it)
            }
        }



//        fun safeGetDocumentId(uri: Uri):String {
//            return try {
//                DocumentsContract.getDocumentId(uri)
//            } catch (e: Throwable) {
//                uri.toString()
//            }
//        }
    }
}

