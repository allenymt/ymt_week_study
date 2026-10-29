package com.vdian.screentime.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.Subject

class Converters {
    @TypeConverter
    fun itemsToString(items: Set<Item>?): String =
        items.orEmpty().joinToString(",") { it.key }

    @TypeConverter
    fun stringToItems(raw: String?): Set<Item> =
        raw.orEmpty().split(",")
            .filter { it.isNotBlank() }
            .map { Item(it) }   // 任意非空 key 都保留，目录里没有的项计 0 分
            .toSet()

    @TypeConverter
    fun subjectToString(subject: Subject?): String = subject?.name.orEmpty()

    @TypeConverter
    fun stringToSubject(raw: String?): Subject =
        Subject.values().firstOrNull { it.name == raw } ?: Subject.ENGLISH
}

@Database(
    entities = [DayEntity::class, WeekEntity::class, ExamEntity::class],
    version = 2,
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

        /** v1 -> v2：新增人工存入/扣减的存钱金额，旧行默认 0。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE week_meta ADD COLUMN savingAdjustCents INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "screen_time.db"
            ).addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
