package no.hanss.alarmclock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Alarm::class, AlarmSeries::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun alarmSeriesDao(): AlarmSeriesDao

    companion object {
        @Volatile private var INSTANCE: AlarmDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeUntilMillis INTEGER")
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
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
