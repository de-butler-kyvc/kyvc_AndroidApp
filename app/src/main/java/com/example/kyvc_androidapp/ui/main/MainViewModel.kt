package com.example.kyvc_androidapp.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.kyvc_androidapp.di.AppContainer

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val appContainer: AppContainer = AppContainer(application)
}
