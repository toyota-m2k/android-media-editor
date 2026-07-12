package io.github.toyota32k.media.editor.project

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import io.github.toyota32k.logger.UtLog
import java.util.Date

@Entity(
    tableName = "t_project",
    indices = [Index(value = ["name"]), Index(value=["sourceUri"], unique = true), Index(value=["hash"], unique = false)])
data class Project(
    @PrimaryKey(autoGenerate = true)
    val id:Int,
    val name:String,
    val type: String,
    val sourceUri: String,
    val copiedUri: String?,            // sourceUri の永続的なアクセス権がない場合は、アプリデータ領域にコピーして管理する
    val serializedChapters: String?,
    val serializedCropParams: String?,
    val resolution:Int,
    val fileTimestamp: Long,
    val lastAccessTime:Long,
    val hash: String,
    ) {
    val copied get() = !copiedUri.isNullOrEmpty()
    val editingUri get() = copiedUri ?: sourceUri

    fun modified(
        name: String = this.name,
        serializedChapters: String? = this.serializedChapters,
        serializedCropParams: String? = this.serializedCropParams,
        resolution: Int = this.resolution,
        hash: String = this.hash,
        ) : Project? {
        if (name==this.name && serializedChapters == this.serializedChapters && serializedCropParams == this.serializedCropParams && this.resolution == resolution && this.hash == hash) {
            return null // 変更がなければ null を返す
        }
        UtLog("DB").debug("ORG=$this")
        return Project(id, name, type, sourceUri, copiedUri, serializedChapters, serializedCropParams, resolution, fileTimestamp, Date().time, hash).apply {UtLog("DB").debug("NEW=$this")}
    }
    val isPhoto:Boolean
        get() = when (type) {
            "jpeg", "jpg", "png", "gif" -> true
            else -> false
        }
    val isVideo:Boolean
        get() = when (type) {
            "mp4" -> true
            else -> false
        }

}

@Dao
interface ProjectTable {
    @Query("SELECT * from t_project ORDER BY lastAccessTime ASC")
    fun getAll(): List<Project>
    @Query("SELECT * from t_project WHERE id = :id")
    fun get(id:Int): Project?
    @Query("SELECT * from t_project WHERE sourceUri = :uri")
    fun getByUri(uri:String): Project?
//    @Query("SELECT * from t_project WHERE hash = :hash")
//    fun getByHash(hash:String): Project?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(project:Project): Long
    @Update
    fun update(project:Project)
    @Delete
    fun delete(project:Project)
}

@Database(entities = [Project::class, KeyValueEntry::class], version = 1, exportSchema = false)
abstract class Database : RoomDatabase() {
    abstract fun projectTable(): ProjectTable
    abstract fun keyValueTable(): KeyValueTable
}

