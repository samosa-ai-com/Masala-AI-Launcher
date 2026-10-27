package com.samosaai.masala.pro.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * `:pro`'s own small Room database (arch §3.1), so dropping `:pro` takes the file index and the export
 * list with it and `MasalaDatabase` never mentions them. Both tables are declared now (plan Phase 1,
 * lane C); the file finder fills `file_index` and the APK export fills `exported_apps` in Phase 5.
 */
@Database(entities = [FileIndexEntry::class, ExportedApp::class], version = 1)
abstract class ProDatabase : RoomDatabase() {
    abstract fun fileIndex(): FileIndexDao
    abstract fun exportedApps(): ExportedAppDao

    companion object {
        fun create(context: Context): ProDatabase =
            Room.databaseBuilder(context, ProDatabase::class.java, "pro.db").build()
    }
}

/**
 * One file in the full-text index behind `file_search`: its [name], and for text files and documents the
 * start of its [content]. [path] and [modified] are stored but not searched.
 */
@Fts4(notIndexed = ["path", "modified"])
@Entity(tableName = "file_index")
data class FileIndexEntry(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val path: String,
    val name: String,
    val content: String,
    val modified: Long,
)

@Dao
interface FileIndexDao {
    @Upsert
    suspend fun upsert(entries: List<FileIndexEntry>)

    /** [query] is an FTS4 MATCH expression, e.g. `tax* 2025`. */
    @Query("SELECT rowid, * FROM file_index WHERE file_index MATCH :query LIMIT :limit")
    suspend fun search(query: String, limit: Int = 50): List<FileIndexEntry>

    @Query("DELETE FROM file_index WHERE path = :path")
    suspend fun deletePath(path: String)

    @Query("DELETE FROM file_index")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM file_index")
    suspend fun count(): Int
}

/** An app exported from a mini app (Phase 5): what was installed, so it can be updated or removed. */
@Entity(tableName = "exported_apps")
data class ExportedApp(
    /** The exported APK's package ID, `<masala id>.apps.<slug>`. */
    @PrimaryKey val packageId: String,
    val miniAppId: String,
    val label: String,
    val versionCode: Int,
    val exportedAt: Long,
)

@Dao
interface ExportedAppDao {
    @Upsert
    suspend fun upsert(app: ExportedApp)

    @Query("SELECT * FROM exported_apps ORDER BY exportedAt DESC")
    suspend fun all(): List<ExportedApp>

    @Query("DELETE FROM exported_apps WHERE packageId = :packageId")
    suspend fun delete(packageId: String)
}
