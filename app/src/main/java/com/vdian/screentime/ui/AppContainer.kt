package com.vdian.screentime.ui

import android.content.Context
import com.vdian.screentime.data.AppDatabase
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.SettingsStore

/** 简单的依赖容器。应用规模小，不需要 DI 框架。 */
object AppContainer {

    @Volatile
    private var repo: ScreenTimeRepository? = null

    fun repository(context: Context): ScreenTimeRepository = repo ?: synchronized(this) {
        repo ?: run {
            val app = context.applicationContext
            val db = AppDatabase.get(app)
            ScreenTimeRepository(
                dayDao = db.dayDao(),
                weekDao = db.weekDao(),
                examDao = db.examDao(),
                settingsStore = SettingsStore(app)
            ).also { repo = it }
        }
    }
}
