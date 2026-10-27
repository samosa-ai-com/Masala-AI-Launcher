package com.samosaai.masala.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 1,
)
abstract class MasalaDatabase : RoomDatabase() {
    abstract fun appOverrides(): AppOverrideDao

    companion object {
        fun create(context: Context): MasalaDatabase =
            Room.databaseBuilder(context, MasalaDatabase::class.java, "masala.db").build()
    }
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
