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
import androidx.room.OnConflictStrategy
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
        WidgetEntity::class,
        WidgetVersionEntity::class,
        StreamSourceEntity::class,
        WidgetHttpValueEntity::class,
        WallpaperEntity::class,
        WallpaperRoutineEntity::class,
        MeetingSessionEntity::class,
        MeetingChunkEntity::class,
        MeetingTurnEntity::class,
    ],
    version = 12,
)
abstract class MasalaDatabase : RoomDatabase() {
    abstract fun appOverrides(): AppOverrideDao
    abstract fun layoutVersions(): LayoutVersionDao
    abstract fun miniApps(): MiniAppDao
    abstract fun skillRuns(): SkillRunDao
    abstract fun skillArtifacts(): SkillArtifactDao
    abstract fun widgetStates(): WidgetStateDao
    abstract fun usageWeights(): UsageWeightDao
    abstract fun widgets(): WidgetDao
    abstract fun widgetVersions(): WidgetVersionDao
    abstract fun streamSources(): StreamSourceDao
    abstract fun httpValues(): WidgetHttpValueDao
    abstract fun wikiPages(): WikiDao
    abstract fun appLaunches(): AppLaunchDao
    abstract fun wallpapers(): WallpaperDao
    abstract fun wallpaperRoutines(): WallpaperRoutineDao
    abstract fun meetings(): MeetingDao
    abstract fun meetingChunks(): MeetingChunkDao
    abstract fun meetingTurns(): MeetingTurnDao

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

        /** Icons: an app can carry a chosen icon, so every row that remembers one keeps it. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_overrides ADD COLUMN icon TEXT")
            }
        }

        /** Internet is always on for mini apps now; existing rows come along. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE mini_apps SET networkAllowed = 1")
            }
        }

        /** Widget library (plan `docs/plans/widget-maker.md` §6.1): specs plus the last versions of each. */
        val MIGRATION_5_6 = object : Migration(5, 6) {            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `widgets` (`id` TEXT NOT NULL, `specJson` TEXT NOT NULL, `tier` TEXT NOT NULL, `title` TEXT, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `widget_versions` (`widgetId` TEXT NOT NULL, `version` INTEGER NOT NULL, `specJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`widgetId`, `version`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_widget_versions_widgetId_version` ON `widget_versions` (`widgetId`, `version`)")
            }
        }

        /** Stream sources (plan `docs/plans/widget-maker.md` §4.2): registry without secrets. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `stream_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `url` TEXT NOT NULL, `snapshotUrl` TEXT, `secret` TEXT, `transport` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        /** Fetched web values (plan `docs/plans/widget-maker.md` §5): display strings and ages. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `widget_http_values` (`key` TEXT NOT NULL, `widgetId` TEXT NOT NULL, `value` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        /** Generated wallpapers (plan `docs/plans/wallpaper-generation.md`): file + prompt catalog. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `wallpapers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fileName` TEXT NOT NULL, `prompt` TEXT NOT NULL, `style` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }

        /** Mini-app media bridge (plan `docs/plans/media-tools.md`): per-app opt-in, default off. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `wallpaper_routines` (`id` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        /**
         * AI meeting notes (plan `docs/plans/meeting-notes.md`): sessions, their 5-minute chunks and
         * the speaker-labelled turns. Audio stays in app-private files; these rows are the index.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meeting_sessions` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER, `status` TEXT NOT NULL, `template` TEXT NOT NULL, " +
                        "`language` TEXT NOT NULL, `activeMs` INTEGER NOT NULL, `speakerNames` TEXT NOT NULL, " +
                        "`doneActions` TEXT NOT NULL, `rawNotes` TEXT NOT NULL, `bookmarks` TEXT NOT NULL, " +
                        "`noteMarkdown` TEXT, `noteJson` TEXT, `brainPath` TEXT, `error` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_sessions_startedAt` ON `meeting_sessions` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meeting_chunks` (" +
                        "`id` TEXT NOT NULL, `meetingId` TEXT NOT NULL, `idx` INTEGER NOT NULL, " +
                        "`path` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `labelsUncertain` INTEGER NOT NULL, " +
                        "`error` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_chunks_meetingId_idx` ON `meeting_chunks` (`meetingId`, `idx`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meeting_turns` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meetingId` TEXT NOT NULL, " +
                        "`chunkIdx` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, " +
                        "`speaker` TEXT, `text` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_turns_meetingId_startMs` ON `meeting_turns` (`meetingId`, `startMs`)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mini_apps ADD COLUMN mediaAllowed INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): MasalaDatabase =
            Room.databaseBuilder(context, MasalaDatabase::class.java, "masala.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12,
                ).build()
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

/**
 * One AI-generated wallpaper (plan `docs/plans/wallpaper-generation.md`): the file under
 * `filesDir/wallpapers/` plus the prompt that made it, so the catalog can search it.
 */
@Entity(tableName = "wallpapers")
data class WallpaperEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val prompt: String,
    val style: String = "",
    val createdAt: Long,
)

/** One saved wallpaper routine: its JSON and when it last changed. */
@Entity(tableName = "wallpaper_routines")
data class WallpaperRoutineEntity(
    @PrimaryKey val id: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface WallpaperRoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: WallpaperRoutineEntity)

    @Query("SELECT * FROM wallpaper_routines ORDER BY updatedAt DESC")
    suspend fun all(): List<WallpaperRoutineEntity>

    @Query("SELECT * FROM wallpaper_routines ORDER BY updatedAt DESC")
    fun watch(): Flow<List<WallpaperRoutineEntity>>

    @Query("DELETE FROM wallpaper_routines WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WallpaperDao {
    @Insert
    suspend fun insert(wallpaper: WallpaperEntity): Long

    @Query("SELECT * FROM wallpapers WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): WallpaperEntity?

    @Query("SELECT * FROM wallpapers ORDER BY createdAt DESC")
    suspend fun all(): List<WallpaperEntity>

    @Query("SELECT * FROM wallpapers WHERE prompt LIKE '%' || :query || '%' OR style LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun search(query: String): List<WallpaperEntity>

    @Query("DELETE FROM wallpapers WHERE id = :id")
    suspend fun delete(id: Long)
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

    @Query("UPDATE mini_apps SET mediaAllowed = :allowed, updatedAt = :now WHERE id = :id")
    suspend fun setMediaAllowed(id: String, allowed: Boolean, now: Long)
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
    /** A chosen icon (`IconSpec`): `symbol:<name>` or one small inline SVG; null draws the app's own. */
    val icon: String? = null,
) {
    fun toOverride() = AppOverride(AppKey.parse(key), label, visibility, pinned = pinnedAt != null, icon = icon)

    /** Nothing left to remember, so the row can go. */
    val isEmpty get() = label == null && visibility == Visibility.DEFAULT && pinnedAt == null && icon == null

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

@Dao
interface WidgetStateDao {
    @Query("SELECT * FROM widget_state WHERE widgetId = :widgetId")
    suspend fun get(widgetId: String): WidgetStateEntity?

    @Query("SELECT * FROM widget_state")
    suspend fun all(): List<WidgetStateEntity>

    @Upsert
    suspend fun upsert(entity: WidgetStateEntity)
}

/** Tap weights for stack ordering and usage ranking (Phase 6): `weight += α(1 − weight)`. */
@Entity(tableName = "usage_weights")
data class UsageWeightEntity(
    @PrimaryKey val itemId: String,
    val weight: Double,
    val updatedAt: Long,
)

@Dao
interface UsageWeightDao {
    @Query("SELECT * FROM usage_weights")
    suspend fun all(): List<UsageWeightEntity>

    @Upsert
    suspend fun upsert(entity: UsageWeightEntity)
}

/** One widget in the library (plan `docs/plans/widget-maker.md` §6.1): the spec as JSON plus who made it. */
@Entity(tableName = "widgets")
data class WidgetEntity(
    @PrimaryKey val id: String,
    val specJson: String,
    /** "spec" for native widgets, "html" for the W6 escape hatch. */
    val tier: String = "spec",
    val title: String?,
    /** "user", "agent" or "template". */
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The last versions of one library widget, for Undo in the manager. */
@Entity(tableName = "widget_versions", primaryKeys = ["widgetId", "version"], indices = [Index("widgetId", "version")])
data class WidgetVersionEntity(
    val widgetId: String,
    val version: Long,
    val specJson: String,
    val createdAt: Long,
)

@Dao
interface WidgetDao {
    @Query("SELECT * FROM widgets ORDER BY updatedAt DESC")
    suspend fun all(): List<WidgetEntity>

    @Query("SELECT * FROM widgets ORDER BY updatedAt DESC")
    fun watch(): Flow<List<WidgetEntity>>

    @Query("SELECT * FROM widgets WHERE id = :id")
    suspend fun get(id: String): WidgetEntity?

    @Upsert
    suspend fun upsert(entity: WidgetEntity)

    @Query("DELETE FROM widgets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WidgetVersionDao {
    @Query("SELECT * FROM widget_versions WHERE widgetId = :widgetId ORDER BY version DESC")
    suspend fun versions(widgetId: String): List<WidgetVersionEntity>

    @Insert
    suspend fun insert(version: WidgetVersionEntity)

    @Query("DELETE FROM widget_versions WHERE widgetId = :widgetId AND version NOT IN (SELECT version FROM widget_versions WHERE widgetId = :widgetId ORDER BY version DESC LIMIT 20)")
    suspend fun prune(widgetId: String)

    @Query("DELETE FROM widget_versions WHERE widgetId = :widgetId")
    suspend fun deleteForWidget(widgetId: String)
}

/** One camera or stream a widget may show (plan `docs/plans/widget-maker.md` §4.2). */
@Entity(tableName = "stream_sources")
data class StreamSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** rtsp, hls, dash, mjpeg, snapshot or progressive. */
    val kind: String,
    /** Never carries userinfo; credentials live in [secret], sealed by the Keystore. */
    val url: String,
    val snapshotUrl: String?,
    /** `base64(iv ‖ ciphertext+tag)` of `username + "\n" + password`, or null. */
    val secret: String?,
    val transport: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface StreamSourceDao {
    @Query("SELECT * FROM stream_sources ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<StreamSourceEntity>

    @Query("SELECT * FROM stream_sources ORDER BY name COLLATE NOCASE")
    fun watch(): Flow<List<StreamSourceEntity>>

    @Query("SELECT * FROM stream_sources WHERE id = :id")
    suspend fun get(id: String): StreamSourceEntity?

    @Upsert
    suspend fun upsert(entity: StreamSourceEntity)

    @Query("DELETE FROM stream_sources WHERE id = :id")
    suspend fun delete(id: String)
}

/** One fetched web value (plan `docs/plans/widget-maker.md` §5): the display string and its age. */
@Entity(tableName = "widget_http_values")
data class WidgetHttpValueEntity(
    @PrimaryKey val key: String,
    val widgetId: String,
    val value: String,
    val fetchedAt: Long,
)

@Dao
interface WidgetHttpValueDao {
    @Query("SELECT * FROM widget_http_values")
    suspend fun all(): List<WidgetHttpValueEntity>

    @Upsert
    suspend fun upsert(entity: WidgetHttpValueEntity)

    @Query("DELETE FROM widget_http_values WHERE widgetId = :widgetId")
    suspend fun deleteForWidget(widgetId: String)
}

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
    /** Always on: every mini app may load from the web. Kept so old rows still read. */
    val networkAllowed: Boolean = true,
    /** Mediated media bridge; Masala opts an app in. Default off. */
    val mediaAllowed: Boolean = false,
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


/**
 * One recorded meeting (plan `docs/plans/meeting-notes.md`). Audio lives in
 * `files/meetings/<id>/chunk-NNN.m4a`; the note is Markdown plus the structured JSON it came from.
 * Status values are in `core/meeting/MeetingStatus` — they are strings so the DB stays a dumb store.
 */
@Entity(tableName = "meeting_sessions", indices = [Index("startedAt")])
data class MeetingSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String,
    val template: String = "general",
    val language: String = "",
    val activeMs: Long = 0,
    /** JSON `{"A":"Priya"}`: the name the user gave a detected speaker. */
    val speakerNames: String = "{}",
    /** JSON array of action indices the user ticked off. */
    val doneActions: String = "[]",
    val rawNotes: String = "",
    /** JSON array of [com.samosaai.masala.core.meeting.MeetingBookmark]. */
    val bookmarks: String = "[]",
    val noteMarkdown: String? = null,
    val noteJson: String? = null,
    val brainPath: String? = null,
    val error: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** One 5-minute recording chunk; the transcription unit that stays under the endpoint's 10-minute cap. */
@Entity(tableName = "meeting_chunks", indices = [Index("meetingId", "idx")])
data class MeetingChunkEntity(
    @PrimaryKey val id: String,
    val meetingId: String,
    val idx: Int,
    val path: String,
    /** Start on the meeting clock (pauses excluded). */
    val startMs: Long,
    val durationMs: Long,
    val status: String,
    val attempts: Int = 0,
    val labelsUncertain: Boolean = false,
    val error: String? = null,
)

/** One speaker-labelled turn, already on the meeting clock. */
@Entity(tableName = "meeting_turns", indices = [Index("meetingId", "startMs")])
data class MeetingTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: String,
    val chunkIdx: Int,
    val startMs: Long,
    val endMs: Long,
    val speaker: String? = null,
    val text: String,
)

/** Who spoke how long, for the Speakers tab. */
data class SpeakerTalk(val speaker: String?, val ms: Long)

@Dao
interface MeetingDao {
    @Upsert
    suspend fun upsert(session: MeetingSessionEntity)

    @Query("SELECT * FROM meeting_sessions WHERE id = :id")
    suspend fun get(id: String): MeetingSessionEntity?

    @Query("SELECT * FROM meeting_sessions ORDER BY startedAt DESC")
    fun all(): Flow<List<MeetingSessionEntity>>

    @Query("SELECT * FROM meeting_sessions WHERE id = :id")
    fun flow(id: String): Flow<MeetingSessionEntity?>

    @Query("SELECT * FROM meeting_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<MeetingSessionEntity>

    @Query("SELECT * FROM meeting_sessions ORDER BY startedAt DESC LIMIT 1")
    fun latestFlow(): Flow<MeetingSessionEntity?>

    @Query("SELECT * FROM meeting_sessions WHERE status IN ('recording', 'paused') ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): MeetingSessionEntity?

    @Query("SELECT * FROM meeting_sessions WHERE status IN ('recording', 'paused') ORDER BY startedAt DESC LIMIT 1")
    fun activeFlow(): Flow<MeetingSessionEntity?>

    @Query("SELECT * FROM meeting_sessions WHERE status IN ('recording', 'paused') AND updatedAt < :before")
    suspend fun stale(before: Long): List<MeetingSessionEntity>

    @Query("UPDATE meeting_sessions SET status = :status, activeMs = :activeMs, endedAt = :endedAt, updatedAt = :at WHERE id = :id")
    suspend fun finish(id: String, status: String, activeMs: Long, endedAt: Long?, at: Long)

    @Query("UPDATE meeting_sessions SET status = :status, updatedAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long)

    @Query("UPDATE meeting_sessions SET activeMs = :activeMs, updatedAt = :at WHERE id = :id")
    suspend fun setActiveMs(id: String, activeMs: Long, at: Long)

    @Query("UPDATE meeting_sessions SET title = :title, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: String, title: String, at: Long)

    @Query("UPDATE meeting_sessions SET speakerNames = :names, updatedAt = :at WHERE id = :id")
    suspend fun setSpeakerNames(id: String, names: String, at: Long)

    @Query("UPDATE meeting_sessions SET doneActions = :done, updatedAt = :at WHERE id = :id")
    suspend fun setDoneActions(id: String, done: String, at: Long)

    @Query("UPDATE meeting_sessions SET rawNotes = :notes, bookmarks = :bookmarks, updatedAt = :at WHERE id = :id")
    suspend fun setNotes(id: String, notes: String, bookmarks: String, at: Long)

    @Query("UPDATE meeting_sessions SET noteMarkdown = :markdown, noteJson = :json, status = :status, error = :error, updatedAt = :at WHERE id = :id")
    suspend fun setNote(id: String, markdown: String?, json: String?, status: String, error: String?, at: Long)

    @Query("UPDATE meeting_sessions SET brainPath = :path, updatedAt = :at WHERE id = :id")
    suspend fun setBrainPath(id: String, path: String?, at: Long)

    @Query("UPDATE meeting_sessions SET error = :error, updatedAt = :at WHERE id = :id")
    suspend fun setError(id: String, error: String?, at: Long)

    @Query("DELETE FROM meeting_sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MeetingChunkDao {
    @Upsert
    suspend fun upsert(chunk: MeetingChunkEntity)

    @Query("SELECT * FROM meeting_chunks WHERE meetingId = :id ORDER BY idx")
    suspend fun byMeeting(id: String): List<MeetingChunkEntity>

    @Query("SELECT * FROM meeting_chunks WHERE meetingId = :id ORDER BY idx")
    fun flow(id: String): Flow<List<MeetingChunkEntity>>

    @Query("SELECT * FROM meeting_chunks WHERE meetingId = :id AND idx = :idx")
    suspend fun byIndex(id: String, idx: Int): MeetingChunkEntity?

    @Query("SELECT * FROM meeting_chunks WHERE meetingId = :id AND status IN ('recorded', 'failed') ORDER BY idx LIMIT 1")
    suspend fun nextPending(id: String): MeetingChunkEntity?

    @Query("SELECT * FROM meeting_chunks WHERE meetingId = :id AND status = 'recorded' ORDER BY idx LIMIT 1")
    suspend fun nextRecorded(id: String): MeetingChunkEntity?

    /** Work the transcriber may take: a closed chunk, or a failed one that still has attempts left. */
    @Query(
        "SELECT * FROM meeting_chunks WHERE meetingId = :id AND " +
            "(status = 'recorded' OR (status = 'failed' AND attempts < :maxAttempts)) ORDER BY idx LIMIT 1",
    )
    suspend fun nextPending(id: String, maxAttempts: Int): MeetingChunkEntity?

    /** Closes a chunk the recorder was writing: it is only now safe to upload. */
    @Query("UPDATE meeting_chunks SET status = 'recorded', durationMs = :durationMs WHERE meetingId = :id AND idx = :idx")
    suspend fun close(id: String, idx: Int, durationMs: Long)

    /** A chunk left open by a killed process cannot be read; keep the row honest about why. */
    @Query("UPDATE meeting_chunks SET status = 'failed', error = :reason WHERE meetingId = :id AND status = 'open'")
    suspend fun abandonOpen(id: String, reason: String)

    /** The UI's Retry: failed chunks get their attempts back and rejoin the queue. */
    @Query("UPDATE meeting_chunks SET status = 'recorded', attempts = 0, error = NULL WHERE meetingId = :id AND status = 'failed'")
    suspend fun retryFailed(id: String)

    @Query("UPDATE meeting_chunks SET status = :status, attempts = attempts + 1, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?)

    @Query("UPDATE meeting_chunks SET status = :status, labelsUncertain = :uncertain, error = :error WHERE id = :id")
    suspend fun finish(id: String, status: String, uncertain: Boolean, error: String?)

    @Query("UPDATE meeting_chunks SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: String, durationMs: Long)

    @Query("SELECT COUNT(*) FROM meeting_chunks WHERE meetingId = :id AND status != 'done'")
    suspend fun remaining(id: String): Int

    /** A worker killed mid-chunk leaves 'transcribing': the next run must pick it up again. */
    @Query("UPDATE meeting_chunks SET status = 'recorded' WHERE meetingId = :id AND status = 'transcribing'")
    suspend fun resetStuck(id: String)

    @Query("SELECT COUNT(*) FROM meeting_chunks WHERE meetingId = :id")
    suspend fun count(id: String): Int

    @Query("DELETE FROM meeting_chunks WHERE meetingId = :id")
    suspend fun deleteByMeeting(id: String)
}

@Dao
interface MeetingTurnDao {
    @Insert
    suspend fun insertAll(turns: List<MeetingTurnEntity>)

    @Query("SELECT * FROM meeting_turns WHERE meetingId = :id ORDER BY startMs")
    fun flow(id: String): Flow<List<MeetingTurnEntity>>

    @Query("SELECT * FROM meeting_turns WHERE meetingId = :id ORDER BY startMs")
    suspend fun byMeeting(id: String): List<MeetingTurnEntity>

    @Query("DELETE FROM meeting_turns WHERE meetingId = :id")
    suspend fun deleteByMeeting(id: String)

    @Query("DELETE FROM meeting_turns WHERE meetingId = :id AND chunkIdx = :chunkIdx")
    suspend fun deleteByChunk(id: String, chunkIdx: Int)

    @Query("DELETE FROM meeting_turns WHERE id = :turnId")
    suspend fun deleteById(turnId: Long)

    @Query("SELECT speaker, SUM(endMs - startMs) AS ms FROM meeting_turns WHERE meetingId = :id GROUP BY speaker ORDER BY ms DESC")
    suspend fun talkTime(id: String): List<SpeakerTalk>
}
