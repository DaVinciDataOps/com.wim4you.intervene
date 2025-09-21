package com.wim4you.intervene
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Firebase
import com.google.firebase.database.database
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.databinding.ActivityMainBinding
import com.wim4you.intervene.distress.DistressReceiver
import com.wim4you.intervene.distress.DistressService
import com.wim4you.intervene.distress.DistressSoundService
import com.wim4you.intervene.location.LocationTrackerService
import com.wim4you.intervene.location.LocationTrackerService.Companion.ACTION_DISTRESS_UPDATE
import com.wim4you.intervene.location.PatrolService
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import com.wim4you.intervene.ui.distressCall.DistressListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var personStore: PersonDataRepository
    private lateinit var vigilanteStore: VigilanteDataRepository
    private val database by lazy { Firebase.database.getReference() }

    private val distressListviewModel: DistressListViewModel by viewModels()
    private val distressListener =  IDistressUpdateListener { distressCalls ->
            distressListviewModel.updateDistressCalls(distressCalls)
        }

    private lateinit var distressUpdateReceiver: DistressReceiver


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        personStore = PersonDataRepository(DatabaseProvider.getDatabase(this).personDataDao())
        vigilanteStore =
            VigilanteDataRepository(DatabaseProvider.getDatabase(this).vigilanteDataDao())
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        binding.appBarMain.fab.setOnClickListener { view ->
            val snackbar = Snackbar.make(view, AppState.snackBarMessage, Snackbar.LENGTH_LONG)
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
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home
            ), drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        fun startTrackingServiceState(activity: Activity) {
            val intent = Intent(activity, LocationTrackerService::class.java)
            activity.startService(intent)
        }

        fun setPatrolServiceState(isPatrolling: Boolean) {
            val attributedContext = createAttributionContext("patrol_service")
            val intent = Intent(attributedContext, PatrolService::class.java)
            if (isPatrolling) {
                attributedContext.startService(intent)
            } else {
                attributedContext.stopService(intent)
            }
        }

        fun startStopDistressServiceState(activity: Activity, isDistress: Boolean) {
            val intent = Intent(activity, DistressService::class.java)
            if (isDistress) {
                activity.startService(intent)
            } else {
                activity.stopService(intent)
            }
        }

        if (!PermissionsUtils.areLocationPermissionsGranted(this)) {
            PermissionsUtils.requestPermissions(this)
        } else {
            // Permissions already granted, start the service
            startTrackingServiceState(this)
        }

        distressUpdateReceiver = DistressReceiver(distressListener)
        val filter = IntentFilter(ACTION_DISTRESS_UPDATE)
        LocalBroadcastManager.getInstance(this).registerReceiver(distressUpdateReceiver, filter)

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_startstop_guided_trip -> {
                    AppState.isGuidedTrip = !AppState.isGuidedTrip
                    if (!AppState.isGuidedTrip)
                        AppState.isDistressState = false

                    navView.menu.findItem(R.id.nav_startstop_patrolling)?.isVisible =
                        !AppState.isGuidedTrip
                    menuItem.title =
                        if (AppState.isGuidedTrip) "Stop guided trip" else "Start guided trip"
                    updateScreenKeepOn(AppState.isGuidedTrip)
                    navController.navigate(R.id.nav_home)
                    true
                }

                R.id.nav_startstop_patrolling -> {
                    AppState.isPatrolling = !AppState.isPatrolling
                    navView.menu.findItem(R.id.nav_startstop_guided_trip)?.isVisible =
                        !AppState.isPatrolling
                    setPatrolServiceState(AppState.isPatrolling)
                    updateScreenKeepOn(AppState.isPatrolling)
                    menuItem.title =
                        if (AppState.isPatrolling) "Stop patrolling" else "Start patrolling"
                    navController.navigate(R.id.nav_home)
                    true
                }

                R.id.nav_distress_list -> {
                    navController.navigate(menuItem.itemId)
                    true
                }

                R.id.nav_stop_distress -> {
                    AppState.isDistressState = false
                    stopSound()
                    startStopDistressServiceState(this, AppState.isDistressState)
                    navController.navigate(R.id.nav_home)
                    true
                }

                else -> {
                    navController.navigate(menuItem.itemId)
                    true
                }
            }
                .also {
                    drawerLayout.closeDrawers()
                }
        }

        PermissionsUtils.requestPermissions(this)

        FirebaseUtils.onConnect(this) { db ->
            FirebaseUtils.getVigilantes(this, db, 2.0);
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun stopSound() {
        AppState.isDistressState = false
        //DistressService.stop(this)
        CoroutineScope(Dispatchers.Main).launch {
            updateDistress()
        }
        // Stop the sound service
        DistressSoundService.stop(this)
    }

    private suspend fun updateDistress() {
        var personData = personStore.fetch();
        database.child("distress").child(personData?.id ?: "")
            .updateChildren(mapOf("active" to false))
            .addOnSuccessListener {
                Log.i("Firebase", "Success saving distress:")
            }
            .addOnFailureListener { exception ->
                Log.e("Firebase", "Error saving distress:")
            }
    }

    private fun updateScreenKeepOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}