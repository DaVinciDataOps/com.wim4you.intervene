package com.wim4you.intervene

import android.app.Application
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.liverecording.LiveRecordingController
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.security.FirebaseDataRetention
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class InterVeneApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applySavedTheme(this)
        DatabaseProvider.getDatabase(this)
        AppModeController.initialize(this)
        LiveRecordingController.initialize(this)
        LiveRecordingController.bindContext(this)
        FirebaseUtils.initialize(this)
        runSecurityStartupTasks()
    }

    private fun runSecurityStartupTasks() {
        applicationScope.launch {
            PersonDataRepository(DatabaseProvider.getDatabase(this@InterVeneApplication).personDataDao())
                .migratePlaintextFieldsIfNeeded()
            FirebaseDataRetention.reconcileOwnEntries()
        }
    }
}
