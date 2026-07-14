package com.wim4you.intervene.proximitychat

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.SecureLog
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.profilepicture.ProfilePictureSharingCoordinator
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.ProximityChatRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes chat presence while the app is in the foreground so nearby users
 * can discover each other without opening the Nearby Chat screen.
 */
@Singleton
class ChatPresenceManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val chatRepository: ProximityChatRepository,
    private val personDataRepository: PersonDataRepository,
    private val vigilanteDataRepository: VigilanteDataRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var presenceJob: Job? = null
    private var myUid: String? = null

    fun start() {
        if (presenceJob?.isActive == true) return
        if (!hasLocationPermission()) return

        presenceJob = scope.launch {
            val uid = try {
                chatRepository.ensureAuthenticated()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to authenticate for chat presence", exception)
                return@launch
            }
            myUid = uid

            val alias = try {
                resolveAlias()
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to resolve chat alias for presence", exception)
                return@launch
            }

            while (isActive) {
                val latLng = LocationUtils.resolveLocationSuspend(appContext)
                if (latLng != null) {
                    try {
                        chatRepository.updatePresence(
                            uid,
                            alias,
                            latLng.latitude,
                            latLng.longitude,
                            ProfilePictureSharingCoordinator.getPublishedUrl(appContext),
                        )
                    } catch (exception: Exception) {
                        SecureLog.e(TAG, "Failed to update chat presence", exception)
                    }
                }
                delay(AppModeController.LOCATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        presenceJob?.cancel()
        presenceJob = null
        val uid = myUid ?: return
        scope.launch {
            try {
                chatRepository.clearPresence(uid)
            } catch (exception: Exception) {
                SecureLog.e(TAG, "Failed to clear chat presence", exception)
            }
        }
    }

    private suspend fun resolveAlias(): String {
        personDataRepository.fetch()?.alias?.takeIf { it.isNotBlank() }?.let { return it }
        vigilanteDataRepository.fetch()?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return "User"
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "ChatPresenceManager"
    }
}
