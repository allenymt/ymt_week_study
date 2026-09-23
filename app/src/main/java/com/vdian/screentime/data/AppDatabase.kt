package com.vdian.screentime.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.Subject

class Converters {
    @TypeConverter
    fun itemsToString(items: Set<Item>?): String =
        items.orEmpty().joinToString(",") { it.name }

    @TypeConverter
    fun stringToItems(raw: String?): Set<Item> =
        raw.orEmpty().split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { name -> Item.values().firstOrNull { it.name == name } }
            .toSet()

    @TypeConverter
    fun subjectToString(subject: Subject?): String = subject?.name.orEmpty()

    @TypeConverter
    fun stringToSubject(raw: String?): Subject =
        Subject.values().firstOrNull { it.name == raw } ?: Subject.ENGLISH
}

@Database(
    entities = [DayEntity::class, WeekEntity::class, ExamEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayDao(): DayDao
    abstract fun weekDao(): WeekDao
    abstract fun examDao(): ExamDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "screen_time.db"
            ).build().also { instance = it }
        }
    }
}
