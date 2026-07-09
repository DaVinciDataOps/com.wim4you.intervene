package com.wim4you.intervene

import android.app.Application
import com.wim4you.intervene.dao.DatabaseProvider
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class InterVeneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applySavedTheme(this)
        DatabaseProvider.getDatabase(this)
        AppModeController.initialize(this)
        FirebaseUtils.initialize(this)
    }
}
