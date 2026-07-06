package no.hanss.alarmclock.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromDaySet(days: Set<Int>): String = days.sorted().joinToString(",")

    @TypeConverter
    fun toDaySet(raw: String): Set<Int> =
        if (raw.isBlank()) emptySet()
        else raw.split(",").map { it.trim().toInt() }.toSet()
}
