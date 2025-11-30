package io.github.toyota32k.media.editor.project

import androidx.room.*

@Entity(tableName = "t_kv")
data class KeyValueEntry (
    @PrimaryKey
    val key_:String,    // key だと @Queryでエラーになる。予約語か何かなのか？　Room ... なんか不自由やな。
    val value:String,
)

@Dao
interface KeyValueTable {
    @Query("SELECT * FROM t_kv")
    fun getAll():List<KeyValueEntry>

    @Query("SELECT * FROM t_kv WHERE key_=:key")
    fun getAt(key:String):KeyValueEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg entry:KeyValueEntry)

    @Delete
    fun delete(vararg entry: KeyValueEntry)

    @Update
    fun update(vararg entry: KeyValueEntry)
}
