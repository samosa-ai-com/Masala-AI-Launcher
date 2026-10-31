package com.samosaai.masala.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.AppOverride
import com.samosaai.masala.core.apps.Visibility
import kotlinx.coroutines.flow.Flow

/**
 * Every table the prototype needs, declared up front (plan, Phase 1 "Shared: data") so later lanes add
 * DAOs, not migrations. `file_index` and `exported_apps` live in `:pro`'s own database. Large or
 * model-written values (layouts, widget state, skill transcripts) are stored as contract JSON.
 */
@Database(
    entities = [
        LayoutVersionEntity::class,
        AppOverrideEntity::class,
        WikiPageEntity::class,
        WidgetStateEntity::class,
        UsageWeightEntity::class,
        SkillRunEntity::class,
        SkillArtifactEntity::class,
        MiniAppEntity::class,
        WatchEntity::class,
        AppLaunchEntity::class,
    ],
    version = 3,
)
abstract class MasalaDatabase : RoomDatabase() {
    abstract fun appOverrides(): AppOverrideDao
    abstract fun layoutVersions(): LayoutVersionDao
    abstract fun miniApps(): MiniAppDao
    abstract fun skillRuns(): SkillRunDao
    abstract fun skillArtifacts(): SkillArtifactDao
    abstract fun wikiPages(): WikiDao
    abstract fun appLaunches(): AppLaunchDao

    companion object {
        /** UI refresh U3: the launch log behind the globe drawer and "Suggested now". */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_launches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `appKey` TEXT NOT NULL, `at` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_launches_at` ON `app_launches` (`at`)")
            }
        }

        /** UI feedback round 2, Batch C: the agent-chosen icon behind mini-app rows, top bar and home. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mini_apps ADD COLUMN icon TEXT")
            }
        }

        fun create(context: Context): MasalaDatabase =
            Room.databaseBuilder(context, MasalaDatabase::class.java, "masala.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}

/**
 * One app launch from home, the drawer, search or the agent (UI refresh U3), for the globe drawer and
 * "Suggested now". Kept 30 days. With Usage access, the system's own counts are used instead.
 */
@Entity(tableName = "app_launches", indices = [Index("at")])
data class AppLaunchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appKey: String,
    val at: Long,
)

@Dao
interface AppLaunchDao {
    @Insert
    suspend fun insert(launch: AppLaunchEntity)

    @Query("SELECT * FROM app_launches WHERE at >= :since")
    suspend fun since(since: Long): List<AppLaunchEntity>

    @Query("DELETE FROM app_launches WHERE at < :before")
    suspend fun prune(before: Long): Int
}

/** A saved home layout (Phase 2): the `LayoutDoc` JSON, labeled for undo and the version list. */
@Entity(tableName = "layout_versions")
data class LayoutVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val schemaVersion: Int,
    val json: String,
    val createdAt: Long,
)

@Dao
interface LayoutVersionDao {
    @Query("SELECT * FROM layout_versions ORDER BY id")
    suspend fun all(): List<LayoutVersionEntity>

    @Query("SELECT * FROM layout_versions WHERE id = :id")
    suspend fun get(id: Long): LayoutVersionEntity?

    @Upsert
    suspend fun upsert(entity: LayoutVersionEntity)

    @Query("DELETE FROM layout_versions WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

/** A generated HTML mini-app (arch §3.9, Phase 3): CRUD lives in `MiniAppStore`, rendering in `MiniAppView`. */
@Dao
interface MiniAppDao {
    @Query("SELECT * FROM mini_apps ORDER BY updatedAt DESC")
    fun all(): Flow<List<MiniAppEntity>>

    @Query("SELECT * FROM mini_apps WHERE id = :id")
    suspend fun get(id: String): MiniAppEntity?

    @Upsert
    suspend fun upsert(entity: MiniAppEntity)

    @Query("DELETE FROM mini_apps WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE mini_apps SET networkAllowed = :allowed, updatedAt = :now WHERE id = :id")
    suspend fun setNetworkAllowed(id: String, allowed: Boolean, now: Long)
}

/** The user's changes to one app in one profile, keyed by [AppKey.id]. */
@Entity(tableName = "app_overrides", indices = [Index("packageName", "user")])
data class AppOverrideEntity(
    @PrimaryKey val key: String,
    val packageName: String,
    val user: Long,
    val label: String? = null,
    val visibility: Visibility = Visibility.DEFAULT,
    /** When it was pinned to home, for ordering; null when it isn't. */
    val pinnedAt: Long? = null,
) {
    fun toOverride() = AppOverride(AppKey.parse(key), label, visibility, pinned = pinnedAt != null)

    /** Nothing left to remember, so the row can go. */
    val isEmpty get() = label == null && visibility == Visibility.DEFAULT && pinnedAt == null

    companion object {
        fun empty(key: AppKey) = AppOverrideEntity(key.id, key.packageName, key.user)
    }
}

@Dao
interface AppOverrideDao {
    @Query("SELECT * FROM app_overrides")
    fun all(): Flow<List<AppOverrideEntity>>

    @Query("SELECT * FROM app_overrides WHERE `key` = :key")
    suspend fun get(key: String): AppOverrideEntity?

    @Upsert
    suspend fun upsert(entity: AppOverrideEntity)

    @Query("DELETE FROM app_overrides WHERE `key` = :key")
    suspend fun delete(key: String)

    /** After an uninstall: forget the package's renames, visibility and pins in that profile. */
    @Query("DELETE FROM app_overrides WHERE packageName = :packageName AND user = :user")
    suspend fun deletePackage(packageName: String, user: Long)
}

/** A Personal Wiki page (arch §3.5, Phase 4). */
@Entity(tableName = "wiki_pages")
data class WikiPageEntity(
    @PrimaryKey val id: String,
    val title: String,
    val markdown: String,
    /** "user", or "agent" for a page the user approved from a proposal. */
    val source: String,
    val updatedAt: Long,
)

@Dao
interface WikiDao {
    @Query("SELECT * FROM wiki_pages ORDER BY title COLLATE NOCASE")
    fun all(): Flow<List<WikiPageEntity>>

    @Query("SELECT * FROM wiki_pages WHERE id = :id")
    suspend fun get(id: String): WikiPageEntity?

    @Upsert
    suspend fun upsert(page: WikiPageEntity)

    @Query("DELETE FROM wiki_pages WHERE id = :id")
    suspend fun delete(id: String)
}

/** Per-widget storage for generated widgets (arch §3.7, Phase 6): counters, checklists and so on. */
@Entity(tableName = "widget_state")
data class WidgetStateEntity(
    @PrimaryKey val widgetId: String,
    val json: String,
    val updatedAt: Long,
)

/** Tap weights for stack ordering and usage ranking (Phase 6): `weight += α(1 − weight)`. */
@Entity(tableName = "usage_weights")
data class UsageWeightEntity(
    @PrimaryKey val itemId: String,
    val weight: Double,
    val updatedAt: Long,
)

/** One run of a skill (arch §3.9, Phase 4). */
@Entity(tableName = "skill_runs", indices = [Index("skillId")])
data class SkillRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: String,
    val prompt: String?,
    /** "running", "done", "failed" or "cancelled". */
    val status: String,
    /** The run's messages and tool calls, as JSON. */
    val transcriptJson: String,
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Dao
interface SkillRunDao {
    @Insert
    suspend fun insert(run: SkillRunEntity): Long

    @Update
    suspend fun update(run: SkillRunEntity)

    @Query("SELECT * FROM skill_runs WHERE id = :id")
    suspend fun get(id: Long): SkillRunEntity?

    /** The newest run of a skill, for the widget's result line and for reopening the sheet. */
    @Query("SELECT * FROM skill_runs WHERE skillId = :skillId ORDER BY startedAt DESC, id DESC LIMIT 1")
    fun latestFor(skillId: String): Flow<SkillRunEntity?>

    @Query("SELECT * FROM skill_runs WHERE skillId = :skillId ORDER BY startedAt DESC, id DESC")
    fun runsFor(skillId: String): Flow<List<SkillRunEntity>>
}

@Dao
interface SkillArtifactDao {
    @Insert
    suspend fun insert(artifact: SkillArtifactEntity): Long

    @Query("SELECT * FROM skill_artifacts WHERE runId = :runId ORDER BY id")
    fun forRun(runId: Long): Flow<List<SkillArtifactEntity>>

    @Query("DELETE FROM skill_artifacts WHERE runId = :runId AND kind = :kind AND ref = :ref")
    suspend fun deleteSame(runId: Long, kind: String, ref: String)
}

/** A result a skill run produced: a note, a file, a mini app, an installed app. */
@Entity(
    tableName = "skill_artifacts",
    foreignKeys = [ForeignKey(SkillRunEntity::class, ["id"], ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId")],
)
data class SkillArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    /** A `MasalaSkill` output kind: markdown, file, audio, video, mini_app, installed_app. */
    val kind: String,
    val title: String,
    /** File path, content URI, mini app ID or package name, depending on [kind]. */
    val ref: String,
    val createdAt: Long,
)

/** A generated HTML app (arch §3.9, Phase 3). Each one is served from its own path. */
@Entity(tableName = "mini_apps")
data class MiniAppEntity(
    @PrimaryKey val id: String,
    val title: String,
    val html: String,
    /** Off unless the user turns it on for this app. */
    val networkAllowed: Boolean = false,
    /** The agent-chosen icon: `symbol:<name>` or one small inline SVG; null draws the title's letter. */
    val icon: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A flight or Amazon price watch (Phase 5). */
@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "flight" or "amazon". */
    val kind: String,
    val title: String,
    /** Route and dates, or ASIN and country, as JSON. */
    val queryJson: String,
    val targetPrice: Double?,
    val currency: String,
    val link: String?,
    /** `[{at, price}]`, oldest first. */
    val historyJson: String = "[]",
    val lastPrice: Double? = null,
    val lastCheckedAt: Long? = null,
    val createdAt: Long,
)
