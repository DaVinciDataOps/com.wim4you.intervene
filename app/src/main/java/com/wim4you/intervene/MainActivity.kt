package com.wim4you.intervene

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuItemCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.databinding.ActivityMainBinding
import com.wim4you.intervene.location.LocationTrackerService
import com.wim4you.intervene.location.MapLocationReceiver
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import com.wim4you.intervene.ui.map.MapDataViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var personStore: PersonDataRepository
    private lateinit var vigilanteStore: VigilanteDataRepository

    private val mapDataViewModel: MapDataViewModel by viewModels()
    private lateinit var mapLocationReceiver: MapLocationReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        personStore = PersonDataRepository(DatabaseProvider.getDatabase(this).personDataDao())
        vigilanteStore = VigilanteDataRepository(DatabaseProvider.getDatabase(this).vigilanteDataDao())
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

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

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        if (PermissionsUtils.areLocationPermissionsGranted(this)) {
            startLocationTrackerService(this)
        } else {
            PermissionsUtils.requestPermissions(this)
        }

        mapLocationReceiver = MapLocationReceiver(mapDataViewModel)
        val filter = IntentFilter().apply {
            addAction(LocationTrackerService.ACTION_PATROL_UPDATE)
            addAction(LocationTrackerService.ACTION_DISTRESS_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(mapLocationReceiver, filter)

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_startstop_guided_trip -> {
                    lifecycleScope.launch { toggleGuidedTrip(navView, navController) }
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
                    lifecycleScope.launch {
                        AppModeController.deactivateDistress(this@MainActivity)
                        navController.navigate(R.id.nav_home)
                    }
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

        PermissionsUtils.requestPermissions(this)

        FirebaseUtils.onConnect(this) { db ->
            FirebaseUtils.getVigilantes(this, db, AppModeController.GEO_QUERY_RADIUS_KM)
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mapLocationReceiver)
        super.onDestroy()
    }

    private suspend fun toggleGuidedTrip(navView: NavigationView, navController: androidx.navigation.NavController) {
        if (AppModeController.isGuidedTrip) {
            AppModeController.stopGuidedTrip(this)
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

    private suspend fun togglePatrol(navView: NavigationView, navController: androidx.navigation.NavController) {
        if (AppModeController.isPatrolling) {
            AppModeController.stopPatrol(this)
            updateScreenKeepOn(false)
        } else {
            val vigilante = vigilanteStore.fetch()
            if (vigilante == null) {
                Toast.makeText(this, R.string.register_vigilante_before_patrol, Toast.LENGTH_SHORT).show()
                navController.navigate(R.id.nav_vigilantes)
                return
            }
            AppModeController.vigilante = vigilante
            if (!AppModeController.startPatrol(this)) return
            updateScreenKeepOn(true)
        }
        refreshDrawerMenu(navView)
        navController.navigate(R.id.nav_home)
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
    }

    private fun startLocationTrackerService(activity: Activity) {
        val intent = Intent(activity, LocationTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.action_build_number)?.title =
            getString(R.string.menu_version, getString(R.string.app_build_number))
        setupThemeSwitches(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        setupThemeSwitches(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_dark_mode, R.id.action_follow_system -> true
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupThemeSwitches(menu: Menu) {
        bindThemeSwitch(
            menuItem = menu.findItem(R.id.action_dark_mode),
            label = getString(R.string.switch_dark_mode),
            isChecked = ThemePreferences.isDarkModeActive(this),
        ) { isChecked ->
            val newMode = if (isChecked) {
                ThemePreferences.Mode.DARK
            } else {
                ThemePreferences.Mode.LIGHT
            }
            if (ThemePreferences.getMode(this) != newMode) {
                ThemePreferences.setMode(this, newMode)
                recreate()
            }
        }

        bindThemeSwitch(
            menuItem = menu.findItem(R.id.action_follow_system),
            label = getString(R.string.follow_system_theme),
            isChecked = ThemePreferences.getMode(this) == ThemePreferences.Mode.SYSTEM,
        ) { isChecked ->
            val newMode = if (isChecked) {
                ThemePreferences.Mode.SYSTEM
            } else if (ThemePreferences.isDarkModeActive(this)) {
                ThemePreferences.Mode.DARK
            } else {
                ThemePreferences.Mode.LIGHT
            }
            if (ThemePreferences.getMode(this) != newMode) {
                ThemePreferences.setMode(this, newMode)
                recreate()
            }
        }
    }

    private fun bindThemeSwitch(
        menuItem: MenuItem?,
        label: String,
        isChecked: Boolean,
        onChecked: (Boolean) -> Unit,
    ) {
        if (menuItem == null) return

        MenuItemCompat.setShowAsAction(menuItem, MenuItemCompat.SHOW_AS_ACTION_NEVER)
        if (menuItem.actionView == null) {
            MenuItemCompat.setActionView(menuItem, R.layout.menu_action_switch)
        }

        menuItem.setOnMenuItemClickListener { true }

        val switch = menuItem.actionView?.findViewById<SwitchMaterial>(R.id.menu_switch) ?: return
        switch.text = label
        switch.jumpDrawablesToCurrentState()
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = isChecked
        switch.setOnCheckedChangeListener { _, checked ->
            onChecked(checked)
        }
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
