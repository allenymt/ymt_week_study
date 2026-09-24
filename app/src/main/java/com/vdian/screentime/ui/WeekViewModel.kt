package com.vdian.screentime.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.WeekBoard
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.WeekDates
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 本周表格的视图模型。界面只读 [board]，不做任何算术。
 *
 * 三态交互的分工：[onChildTap] 只把孩子申报放进 pending（不计分），
 * [onParentTap] 才是真正计分的确认，[onParentLongPress] 退回孩子申报。
 */
class WeekViewModel(private val repo: ScreenTimeRepository) : ViewModel() {

    private val _board = MutableLiveData<WeekBoard>()
    val board: LiveData<WeekBoard> = _board

    /** 孩子模式开关。由界面上的开关控制，默认关闭（家长模式）。 */
    val childMode = MutableLiveData(false)

    private var weekStart: Long = WeekDates.weekStart(todayEpochDay())

    /** 当前周的订阅。切周时先取消，避免旧周的 collector 继续写入。 */
    private var observeJob: Job? = null

    init {
        observe()
    }

    private fun observe() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repo.observeWeek(weekStart).collectLatest { _board.value = it }
        }
    }

    fun prevWeek() {
        weekStart -= 7
        observe()
    }

    fun nextWeek() {
        weekStart += 7
        observe()
    }

    fun onChildTap(epochDay: Long, item: Item) {
        viewModelScope.launch { repo.toggleChildClaim(weekStart, epochDay, item) }
    }

    fun onParentTap(epochDay: Long, item: Item) {
        viewModelScope.launch { repo.toggleParentConfirm(weekStart, epochDay, item) }
    }

    fun onParentLongPress(epochDay: Long, item: Item) {
        viewModelScope.launch { repo.retractClaim(weekStart, epochDay, item) }
    }

    fun currentWeekStart(): Long = weekStart

    private fun todayEpochDay(): Long = CURRENT_EPOCH_DAY_FN()

    companion object {
        /**
         * 取本地时间的今天。`System.currentTimeMillis() / 86_400_000` 在 UTC+8 的清晨会退回昨天。
         * 抽成可替换的函数，便于日后注入固定时钟做测试。
         */
        internal var CURRENT_EPOCH_DAY_FN: () -> Long = { localTodayEpochDay() }

        private fun localTodayEpochDay(): Long {
            val cal = Calendar.getInstance()
            return WeekDates.toEpochDay(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        }
    }
}
