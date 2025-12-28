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
    indices = [Index(value = ["name"]), Index(value=["uri"], unique = true)])
data class Project(
    @PrimaryKey(autoGenerate = true)
    val id:Int,
    val name:String,
    val documentId: String,
    val type: String,
    val uri: String,
    val copied:Boolean,
    val serializedChapters: String?,
    val serializedCropParams: String?,
    val resolution:Int,
    val fileTimestamp: Long,
    val lastAccessTime:Long,
    ) {
    fun modified(
        name: String = this.name,
        uri: String = this.uri,
        serializedChapters: String? = this.serializedChapters,
        serializedCropParams: String? = this.serializedCropParams,
        resolution: Int = this.resolution) : Project? {
        if (name==this.name && uri == this.uri && serializedChapters == this.serializedChapters && serializedCropParams == this.serializedCropParams && this.resolution == resolution) {
            return null // 変更がなければ null を返す
        }
        UtLog("DB").debug("ORG=$this")
        return Project(id, name, documentId, type, uri, copied, serializedChapters, serializedCropParams, resolution, fileTimestamp, Date().time).apply {UtLog("DB").debug("NEW=$this")}
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
    @Query("SELECT * from t_project WHERE documentId = :documentId")
    fun get(documentId:String): Project?
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

