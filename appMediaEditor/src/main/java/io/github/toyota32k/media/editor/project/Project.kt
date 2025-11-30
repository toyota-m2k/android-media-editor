package io.github.toyota32k.media.editor.project

import android.provider.DocumentsContract
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import java.util.Date

@Entity(
    tableName = "t_project",
    indices = [Index(value = ["name"], unique=true)])
data class Project(
    @PrimaryKey(autoGenerate = true)
    val id:Int,
    val name:String,
    val documentId: String,
    val type: String,
    val uri: String?,
    val serializedChapters: String?,
    var serializedCropParams: String?,
    val creationTime: Long,
    val lastAccessTime:Long
    )
{
    @Ignore
    val isPersisted:Boolean = uri!=null

    fun modified(
        uri: String? = this.uri,
        serializedChapters: String? = this.serializedChapters,
        serializedCropParams: String? = this.serializedCropParams ) : Project? {
        if (uri == this.uri && serializedChapters == this.serializedChapters && serializedCropParams == this.serializedCropParams) {
            return null // 変更がなければ null を返す
        }
        return Project(id, name, documentId, type, uri, serializedChapters, serializedCropParams, creationTime, Date().time)
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

