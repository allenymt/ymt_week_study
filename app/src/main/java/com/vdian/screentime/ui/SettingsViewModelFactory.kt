package com.vdian.screentime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.SettingsStore

class SettingsViewModelFactory(
    private val repo: ScreenTimeRepository,
    private val settings: SettingsStore
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(repo, settings) as T
}
