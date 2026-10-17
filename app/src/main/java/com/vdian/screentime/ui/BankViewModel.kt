package com.vdian.screentime.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdian.screentime.data.BankRollup
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.WeekBoard
import com.vdian.screentime.domain.WeekDates
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 银行页的视图模型。界面只读 [rollup] 与 [board]，不做任何算术。
 *
 * [rollup] 供大字余额与累计数字使用；[board] 供兑换对话框取本周额度与规则参数。
 */
class BankViewModel(private val repo: ScreenTimeRepository) : ViewModel() {

    private val _rollup = MutableLiveData<BankRollup>()
    val rollup: LiveData<BankRollup> = _rollup

    private val _board = MutableLiveData<WeekBoard>()
    val board: LiveData<WeekBoard> = _board

    private val weekStart: Long = WeekDates.weekStart(todayEpochDay())

    init {
        viewModelScope.launch {
            repo.observeBankRollup(weekStart).collectLatest { _rollup.value = it }
        }
        viewModelScope.launch {
            repo.observeWeek(weekStart).collectLatest { _board.value = it }
        }
    }

    /**
     * 兑换。仓库层会再按本周额度与金额上限夹一次，界面传多少都不会超发。
     * 星期在派发时刻就捕获好，避免写入落到别的周去。
     */
    fun redeem(minutes: Int) {
        val week = weekStart
        viewModelScope.launch { repo.redeem(week, minutes) }
    }

    fun recordUsage(minutes: Int) {
        val week = weekStart
        viewModelScope.launch { repo.addUsedMinutes(week, minutes) }
    }

    fun recordOvertime(minutes: Int) {
        val week = weekStart
        viewModelScope.launch { repo.applyOvertime(week, minutes) }
    }

    /** 人工存入（正）/ 扣减（负）存钱金额，单位为分。 */
    fun adjustSaving(deltaCents: Int) {
        val week = weekStart
        viewModelScope.launch { repo.adjustSaving(week, deltaCents) }
    }

    private fun todayEpochDay(): Long = WeekViewModel.CURRENT_EPOCH_DAY_FN()

    companion object {
        /** 兑换分钟数只能取一个兑换单位的整数倍，选项按这个粒度生成。 */
        fun redeemOptions(cfg: com.vdian.screentime.domain.RuleConfig, maxMinutes: Int): List<Int> {
            if (maxMinutes < cfg.redeemUnitMinutes) return emptyList()
            return (cfg.redeemUnitMinutes..maxMinutes step cfg.redeemUnitMinutes).toList()
        }
    }
}
