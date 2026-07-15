package no.hanss.alarmclock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Alarm::class, AlarmSeries::class, TimerPreset::class, Reminder::class], version = 12, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun alarmSeriesDao(): AlarmSeriesDao
    abstract fun timerDao(): TimerDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile private var INSTANCE: AlarmDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeUntilMillis INTEGER")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarm_series ADD COLUMN pausedUntilMillis INTEGER")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN pausedUntilMillis INTEGER")
            }
        }

        // New reminders table only -- alarms/series/timers untouched, so
        // everything saved survives this upgrade (same shape as MIGRATION_5_6).
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`dueAtMillis` INTEGER NOT NULL, " +
                        "`state` INTEGER NOT NULL, " +
                        "`repeatType` INTEGER NOT NULL, " +
                        "`repeatInterval` INTEGER NOT NULL, " +
                        "`repeatDaysOfWeek` TEXT NOT NULL, " +
                        "`repeatDayOfMonth` INTEGER NOT NULL, " +
                        "`repeatWeekday` INTEGER NOT NULL, " +
                        "`repeatWeekOfMonth` INTEGER NOT NULL, " +
                        "`snoozedUntilMillis` INTEGER)"
                )
            }
        }

        // New timers table only -- the alarms/alarm_series tables are untouched,
        // so existing alarms survive this upgrade (see the policy note below).
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`durationSeconds` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`vibrate` INTEGER NOT NULL, " +
                        "`soundUri` TEXT, " +
                        "`runningUntilMillis` INTEGER)"
                )
            }
        }

        // #59: per-reminder re-alert interval; default preserves the old
        // fixed daily nag for every existing row.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `renotifyMinutes` INTEGER NOT NULL DEFAULT 1440"
                )
            }
        }

        // #60: per-reminder swipe re-show; -1 = follow the global setting,
        // so every existing reminder keeps its current behavior.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `reshowMinutes` INTEGER NOT NULL DEFAULT -1"
                )
            }
        }

        // #61: one-and-done toggle; existing reminders stay persistent.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `persistent` INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        fun getInstance(context: Context): AlarmDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlarmDatabase::class.java,
                    "alarm_clock.db"
                )
                    // POLICY CHANGE from the early dev phase: every schema bump from
                    // v5 onward MUST ship a real Migration like MIGRATION_4_5 above --
                    // a version bump without one silently WIPES every saved alarm via
                    // the destructive fallback below. The fallback stays only as a
                    // last resort (e.g. a downgrade after a bad flash), because a
                    // wiped alarm list is still better than an alarm app that crashes
                    // on database open and can't ring at all. If a wipe is ever
                    // unavoidable, the release notes must flag it loudly.
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
