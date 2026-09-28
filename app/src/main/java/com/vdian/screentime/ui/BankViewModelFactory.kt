package com.vdian.screentime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vdian.screentime.data.ScreenTimeRepository

class BankViewModelFactory(private val repo: ScreenTimeRepository) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BankViewModel(repo) as T
}
