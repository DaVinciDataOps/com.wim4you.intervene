package com.wim4you.intervene

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wim4you.intervene.databinding.ActivityMainBinding
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.helpers.NetworkUtils
import com.wim4you.intervene.liverecording.LiveRecordingController
import com.wim4you.intervene.liverecording.LiveRecordingPermissions
import com.wim4you.intervene.location.LocationTrackerService
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.location.PatrolFirebaseWriter
import com.wim4you.intervene.proximitychat.ChatPresenceManager
import com.wim4you.intervene.repository.MapLocationRepository
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import com.wim4you.intervene.ui.distresscall.SafeWordDialog
import com.wim4you.intervene.ui.home.HomeViewModel
import com.wim4you.intervene.ui.home.RouteState
import com.wim4you.intervene.ui.map.MapDataViewModel
import com.wim4you.intervene.security.FirebaseDataRetention
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    @Inject lateinit var personStore: PersonDataRepository
    @Inject lateinit var vigilanteStore: VigilanteDataRepository
    @Inject lateinit var mapLocationRepository: MapLocationRepository
    @Inject lateinit var chatPresenceManager: ChatPresenceManager

    private val homeViewModel: HomeViewModel by viewModels()
    private val mapDataViewModel: MapDataViewModel by viewModels()

    private var overflowPopup: PopupWindow? = null
    private var reopenOverflowPopup = false
    private val locationPermissionListeners = mutableSetOf<OnLocationPermissionGrantedListener>()
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val foregroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (PermissionsUtils.hasForegroundLocationPermission(this) &&
            PermissionsUtils.hasForegroundServiceLocationPermission(this)
        ) {
            onForegroundLocationGranted()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Background location is optional; foreground tracking already works.
    }

    private val liveRecordingPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = LiveRecordingPermissions.requiredPermissions().all { permission ->
            results[permission] == true
        }
        if (granted) {
            openLiveRecordingCapture(
                binding.navView,
                findNavController(R.id.nav_host_fragment_content_main),
            )
        } else {
            Toast.makeText(this, R.string.live_recording_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    fun addLocationPermissionListener(listener: OnLocationPermissionGrantedListener) {
        locationPermissionListeners.add(listener)
    }

    fun removeLocationPermissionListener(listener: OnLocationPermissionGrantedListener) {
        locationPermissionListeners.remove(listener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reopenOverflowPopup = savedInstanceState?.getBoolean(KEY_REOPEN_OVERFLOW, false) ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)
        setupCustomOverflowMenu(binding.appBarMain.toolbar)
        if (reopenOverflowPopup) {
            reopenOverflowPopup = false
            openOverflowPopup()
        }

        binding.appBarMain.fab.setOnClickListener { view ->
            val snackbar = Snackbar.make(view, AppModeController.snackBarMessage, Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .setAnchorView(R.id.fab)

            val snackbarView = snackbar.view
            val textView = snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            textView.isSingleLine = false
            textView.maxLines = 10
            snackbar.show()
        }

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_home),
            drawerLayout
        )

        refreshDrawerMenu(navView)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.routeState.collectLatest {
                    refreshDrawerMenu(navView)
                }
            }
        }

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        AppModeController.reconcileServices(this)
        lifecycleScope.launch { FirebaseDataRetention.reconcileOwnEntries() }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                chatPresenceManager.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                chatPresenceManager.stop()
            }
        })

        if (PermissionsUtils.hasForegroundLocationPermission(this)) {
            startLocationTrackerService(this)
            requestBackgroundLocationIfNeeded()
        } else {
            promptForForegroundLocation()
        }

        if (AppModeController.isPatrolling || AppModeController.isGuidedTrip || AppModeController.isDistressActive) {
            updateScreenKeepOn(true)
        }

        if (AppModeController.isPatrolling) {
            lifecycleScope.launch { restorePatrolMarkerIfNeeded() }
        }

        if (AppModeController.isDistressActive) {
            lifecycleScope.launch { restoreDistressMarkerIfNeeded() }
        }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_startstop_guided_trip -> {
                    lifecycleScope.launch { toggleGuidedTrip(navView, navController) }
                    true
                }
                R.id.nav_clear_route -> {
                    homeViewModel.clearRoute(mapDataViewModel.selectedDistressId.value)
                    navController.navigate(R.id.nav_home)
                    true
                }
                R.id.nav_startstop_patrolling -> {
                    lifecycleScope.launch { togglePatrol(navView, navController) }
                    true
                }
                R.id.nav_distress_list -> {
                    navController.navigate(menuItem.itemId)
                    true
                }
                R.id.nav_stop_distress -> {
                    confirmCancelDistress(navController)
                    true
                }
                R.id.nav_startstop_live_recording -> {
                    toggleLiveRecording(navView)
                    true
                }
                R.id.nav_recordings -> {
                    navController.navigate(menuItem.itemId)
                    true
                }
                else -> {
                    navController.navigate(menuItem.itemId)
                    true
                }
            }.also {
                drawerLayout.closeDrawers()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_REOPEN_OVERFLOW, reopenOverflowPopup)
    }

    override fun onDestroy() {
        overflowPopup?.dismiss()
        overflowPopup = null
        super.onDestroy()
    }

    private suspend fun toggleGuidedTrip(navView: NavigationView, navController: androidx.navigation.NavController) {
        if (AppModeController.isGuidedTrip) {
            AppModeController.stopGuidedTrip(this)
            homeViewModel.clearRoute(mapDataViewModel.selectedDistressId.value)
            mapLocationRepository.clearOwnDistress()
            updateScreenKeepOn(false)
        } else {
            val person = personStore.fetch()
            if (person == null) {
                Toast.makeText(this, R.string.register_before_guided_trip, Toast.LENGTH_SHORT).show()
                navController.navigate(R.id.nav_register)
                return
            }
            if (!AppModeController.startGuidedTrip()) return
            updateScreenKeepOn(true)
        }
        refreshDrawerMenu(navView)
        navController.navigate(R.id.nav_home)
    }

    private fun toggleLiveRecording(navView: NavigationView) {
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        if (LiveRecordingController.isRecording ||
            navController.currentDestination?.id == R.id.nav_live_recording_capture
        ) {
            LiveRecordingController.stopRecording(this)
            if (navController.currentDestination?.id == R.id.nav_live_recording_capture) {
                navController.popBackStack()
            }
            Toast.makeText(this, R.string.live_recording_stopped, Toast.LENGTH_SHORT).show()
            refreshDrawerMenu(navView)
            return
        }

        if (!AppModeController.isGuidedTrip) {
            Toast.makeText(this, R.string.live_recording_guided_trip_required, Toast.LENGTH_SHORT).show()
            return
        }

        if (LiveRecordingPermissions.hasAllPermissions(this)) {
            openLiveRecordingCapture(navView, navController)
        } else {
            liveRecordingPermissionLauncher.launch(LiveRecordingPermissions.requiredPermissions())
        }
    }

    private fun openLiveRecordingCapture(navView: NavigationView, navController: androidx.navigation.NavController) {
        navController.navigate(R.id.nav_live_recording_capture)
        refreshDrawerMenu(navView)
    }

    private suspend fun togglePatrol(navView: NavigationView, navController: androidx.navigation.NavController) {
        if (AppModeController.isPatrolling) {
            AppModeController.stopPatrol(this)
            homeViewModel.clearRoute(mapDataViewModel.selectedDistressId.value)
            mapDataViewModel.clearFocusedDistress()
            mapLocationRepository.clearOwnPatrol()
            updateScreenKeepOn(false)
        } else {
            val vigilante = vigilanteStore.fetch()
            if (vigilante == null) {
                Toast.makeText(this, R.string.register_vigilante_before_patrol, Toast.LENGTH_SHORT).show()
                navController.navigate(R.id.nav_vigilantes)
                return
            }
            AppModeController.vigilante = vigilante
            if (!NetworkUtils.isOnline(this)) {
                Toast.makeText(this, R.string.error_no_network_distress, Toast.LENGTH_SHORT).show()
                return
            }
            if (!AppModeController.startPatrol(this)) return
            registerPatrolInFirebase(vigilante)
            updateScreenKeepOn(true)
        }
        refreshDrawerMenu(navView)
        navController.navigate(R.id.nav_home)
    }

    private suspend fun registerPatrolInFirebase(vigilante: VigilanteData) {
        if (!PermissionsUtils.hasForegroundLocationPermission(this)) {
            Toast.makeText(this, R.string.status_no_location_permission, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val latLng = LocationUtils.resolveLocationSuspend(this)
            if (latLng == null) {
                Toast.makeText(this, R.string.patrol_location_required, Toast.LENGTH_LONG).show()
                AppModeController.reportBackgroundFailure(getString(R.string.patrol_location_required))
                return
            }
            mapLocationRepository.setOwnPatrol(
                vigilante = vigilante,
                latitude = latLng.latitude,
                longitude = latLng.longitude,
            )
            PatrolFirebaseWriter.pushPatrol(
                vigilante = vigilante,
                latitude = latLng.latitude,
                longitude = latLng.longitude,
                context = applicationContext,
            )
            Log.i(TAG, "Patrol registered in Firebase for vigilante ${vigilante.id}")
        } catch (exception: Exception) {
            Log.e(TAG, "Patrol Firebase registration failed", exception)
            val messageRes = when (FirebaseAuthManager.authFailureKey(exception)) {
                "auth_not_configured" -> R.string.chat_error_auth_not_configured
                "auth_anonymous_disabled" -> R.string.chat_error_auth_anonymous_disabled
                "auth_network" -> R.string.chat_error_auth_network
                else -> R.string.patrol_sync_failed
            }
            Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
            AppModeController.reportBackgroundFailure(getString(messageRes))
        }
    }

    private suspend fun restorePatrolMarkerIfNeeded() {
        val vigilante = AppModeController.vigilante ?: vigilanteStore.fetch() ?: return
        AppModeController.vigilante = vigilante
        registerPatrolInFirebase(vigilante)
    }

    private suspend fun restoreDistressMarkerIfNeeded() {
        val person = AppModeController.person ?: personStore.fetch() ?: return
        AppModeController.person = person
        publishOwnDistressMarker(person)
    }

    private fun publishOwnDistressMarker(person: PersonData) {
        LocationUtils.resolveLocation(this) { latLng ->
            if (latLng != null) {
                lifecycleScope.launch {
                    val firebaseUid = try {
                        FirebaseAuthManager.ensureSignedIn()
                    } catch (exception: Exception) {
                        FirebaseAuthManager.currentUid()
                    }
                    mapLocationRepository.setOwnDistress(
                        person = person,
                        latitude = latLng.latitude,
                        longitude = latLng.longitude,
                        firebaseUid = firebaseUid,
                    )
                }
            }
        }
    }

    private fun refreshDrawerMenu(navView: NavigationView) {
        val menuPatrolling = navView.menu.findItem(R.id.nav_startstop_patrolling)
        menuPatrolling.title = if (AppModeController.isPatrolling) {
            getString(R.string.nav_stop_patrolling)
        } else {
            getString(R.string.nav_startstop_patrolling)
        }
        menuPatrolling.isVisible = !AppModeController.isGuidedTrip

        val menuTrip = navView.menu.findItem(R.id.nav_startstop_guided_trip)
        menuTrip.title = if (AppModeController.isGuidedTrip) {
            getString(R.string.nav_stop_guided_trip)
        } else {
            getString(R.string.nav_startstop_guided_trip)
        }
        menuTrip.isVisible = !AppModeController.isPatrolling

        val menuClearRoute = navView.menu.findItem(R.id.nav_clear_route)
        menuClearRoute.isVisible = homeViewModel.routeState.value is RouteState.Success

        val menuLiveRecording = navView.menu.findItem(R.id.nav_startstop_live_recording)
        menuLiveRecording.title = if (LiveRecordingController.isRecording) {
            getString(R.string.nav_stop_live_recording)
        } else {
            getString(R.string.nav_start_live_recording)
        }
        menuLiveRecording.isVisible = AppModeController.isGuidedTrip ||
            LiveRecordingController.isRecording
    }

    private fun startLocationTrackerService(activity: Activity) {
        val intent = Intent(activity, LocationTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }

    private fun promptForForegroundLocation() {
        showForegroundLocationRationaleDialog()
    }

    private fun showForegroundLocationRationaleDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_title)
            .setMessage(R.string.location_permission_rationale)
            .setPositiveButton(R.string.location_permission_allow) { _, _ ->
                requestForegroundLocation()
            }
            .setNegativeButton(R.string.location_permission_not_now, null)
            .show()
    }

    private fun requestForegroundLocation() {
        foregroundLocationLauncher.launch(PermissionsUtils.foregroundLocationPermissions())
    }

    private fun onForegroundLocationGranted() {
        startLocationTrackerService(this)
        AppModeController.reconcileServices(this)
        locationPermissionListeners.forEach { it.onForegroundLocationGranted() }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            chatPresenceManager.start()
        }
        requestBackgroundLocationIfNeeded()
    }

    private fun confirmCancelDistress(navController: androidx.navigation.NavController) {
        SafeWordDialog.show(
            context = this,
            title = getString(R.string.safe_word_cancel_title),
            message = getString(R.string.safe_word_cancel_message),
        ) { safeWord ->
            lifecycleScope.launch {
                val person = AppModeController.person ?: personStore.fetch()
                if (person == null) {
                    Toast.makeText(this@MainActivity, R.string.safe_word_incorrect, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (person.safeWord.trim() != safeWord.trim()) {
                    Toast.makeText(this@MainActivity, R.string.safe_word_incorrect, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AppModeController.deactivateDistress(this@MainActivity)
                mapLocationRepository.clearOwnDistress()
                updateScreenKeepOn(false)
                refreshDrawerMenu(binding.navView)
                navController.navigate(R.id.nav_home)
            }
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (!PermissionsUtils.needsBackgroundLocationPermission(this)) return
        // Theme changes recreate the Activity, so without persistence we would keep re-showing
        // the background-location rationale. We only ask once per app install / data set.
        if (prefs.getBoolean(KEY_BACKGROUND_RATIONALE_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_BACKGROUND_RATIONALE_SHOWN, true).apply()
        showBackgroundLocationRationaleDialog()
    }

    private fun showBackgroundLocationRationaleDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_background_title)
            .setMessage(R.string.location_permission_background_rationale)
            .setPositiveButton(R.string.location_permission_allow) { _, _ ->
                requestBackgroundLocation()
            }
            .setNegativeButton(R.string.location_permission_not_now, null)
            .show()
    }

    private fun requestBackgroundLocation() {
        backgroundLocationLauncher.launch(PermissionsUtils.backgroundLocationPermissions())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    private fun setupCustomOverflowMenu(toolbar: Toolbar) {
        toolbar.post {
            val overflowButton = findOverflowMenuButton(toolbar) ?: return@post
            overflowButton.setOnClickListener {
                openOverflowPopup()
            }
        }
    }

    private fun openOverflowPopup() {
        val overflowButton = findOverflowMenuButton(binding.appBarMain.toolbar) ?: return
        showOverflowPopup(overflowButton)
    }

    private fun findOverflowMenuButton(toolbar: Toolbar): View? {
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView) {
                for (j in 0 until child.childCount) {
                    val menuChild = child.getChildAt(j)
                    if (menuChild.javaClass.simpleName.contains("OverflowMenuButton", ignoreCase = true)) {
                        return menuChild
                    }
                }
            }
        }
        return null
    }

    private fun showOverflowPopup(anchor: View) {
        overflowPopup?.dismiss()

        val popupView = layoutInflater.inflate(R.layout.popup_overflow_menu, null)
        val versionText = popupView.findViewById<TextView>(R.id.menu_version)
        val darkSwitch = popupView.findViewById<SwitchMaterial>(R.id.switch_dark_mode)
        val systemSwitch = popupView.findViewById<SwitchMaterial>(R.id.switch_follow_system)
        val settingsItem = popupView.findViewById<TextView>(R.id.menu_settings)
        val aboutItem = popupView.findViewById<TextView>(R.id.menu_about)

        versionText.text = getString(R.string.menu_version, getString(R.string.app_build_number))

        darkSwitch.setOnCheckedChangeListener(null)
        darkSwitch.isChecked = ThemePreferences.isDarkModeActive(this)
        systemSwitch.setOnCheckedChangeListener(null)
        systemSwitch.isChecked = ThemePreferences.getMode(this) == ThemePreferences.Mode.SYSTEM
        bindOverflowSwitchListeners(darkSwitch, systemSwitch)

        settingsItem.setOnClickListener {
            overflowPopup?.dismiss()
            findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_settings)
        }

        aboutItem.setOnClickListener {
            overflowPopup?.dismiss()
            showAboutDialog()
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        popup.isOutsideTouchable = true
        popup.elevation = 8f * resources.displayMetrics.density

        val xOffset = anchor.width - popupView.measuredWidth
        popup.showAsDropDown(anchor, xOffset, 0, Gravity.END)
        overflowPopup = popup
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_about)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshOverflowSwitchStates(
        darkSwitch: SwitchMaterial,
        systemSwitch: SwitchMaterial,
    ) {
        darkSwitch.setOnCheckedChangeListener(null)
        darkSwitch.isChecked = ThemePreferences.isDarkModeActive(this)
        systemSwitch.setOnCheckedChangeListener(null)
        systemSwitch.isChecked = ThemePreferences.getMode(this) == ThemePreferences.Mode.SYSTEM
        bindOverflowSwitchListeners(darkSwitch, systemSwitch)
    }

    private fun bindOverflowSwitchListeners(
        darkSwitch: SwitchMaterial,
        systemSwitch: SwitchMaterial,
    ) {
        darkSwitch.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) {
                ThemePreferences.Mode.DARK
            } else {
                ThemePreferences.Mode.LIGHT
            }
            if (ThemePreferences.getMode(this) != newMode) {
                reopenOverflowPopup = true
                ThemePreferences.setMode(this, newMode)
                refreshOverflowSwitchStates(darkSwitch, systemSwitch)
            }
        }
        systemSwitch.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) {
                ThemePreferences.Mode.SYSTEM
            } else if (ThemePreferences.isDarkModeActive(this)) {
                ThemePreferences.Mode.DARK
            } else {
                ThemePreferences.Mode.LIGHT
            }
            if (ThemePreferences.getMode(this) != newMode) {
                reopenOverflowPopup = true
                ThemePreferences.setMode(this, newMode)
                refreshOverflowSwitchStates(darkSwitch, systemSwitch)
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val KEY_REOPEN_OVERFLOW = "reopen_overflow_popup"
        private const val PREFS_NAME = "intervene_prefs"
        private const val KEY_BACKGROUND_RATIONALE_SHOWN = "background_location_rationale_shown"
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun updateScreenKeepOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
