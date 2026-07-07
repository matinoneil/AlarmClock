package no.hanss.alarmclock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Alarm::class, AlarmSeries::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun alarmSeriesDao(): AlarmSeriesDao

    companion object {
        @Volatile private var INSTANCE: AlarmDatabase? = null

        fun getInstance(context: Context): AlarmDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlarmDatabase::class.java,
                    "alarm_clock.db"
                )
                    // The app is still under active development, so rather than writing
                    // a Migration for every schema tweak, just recreate the DB on version
                    // bumps. This wipes any alarms saved under an older schema version.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
