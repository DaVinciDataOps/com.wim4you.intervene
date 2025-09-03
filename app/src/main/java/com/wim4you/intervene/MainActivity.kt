package com.wim4you.intervene
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.gms.maps.GoogleMap
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.Firebase
import com.google.firebase.database.database
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.databinding.ActivityMainBinding
import com.wim4you.intervene.fbdata.PatrolData
import com.wim4you.intervene.location.LocationService
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.location.PatrolService
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity()  {
    private lateinit var mMap: GoogleMap
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var personStore: PersonDataRepository
    private lateinit var vigilanteStore: VigilanteDataRepository
    private val database by lazy { Firebase.database.getReference() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        personStore = PersonDataRepository(DatabaseProvider.getDatabase(this).personDataDao())
        vigilanteStore = VigilanteDataRepository(DatabaseProvider.getDatabase(this).vigilanteDataDao())
     binding = ActivityMainBinding.inflate(layoutInflater)
     setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null)
                    .setAnchorView(R.id.fab).show()
        }
        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(setOf(
            R.id.nav_home, R.id.nav_vigilantes, R.id.nav_settings), drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        fun updatePatrolState(activity: Activity, isPatrolling: Boolean) {
            val intent = Intent(activity, PatrolService::class.java)
            if (isPatrolling) {
                activity.startService(intent)
            } else {
                activity.stopService(intent)
            }
        }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_startstop_guided_trip -> {
                    AppState.isGuidedTrip = !AppState.isGuidedTrip
                    if(!AppState.isGuidedTrip)
                        AppState.isDistressState = false

                    navView.menu.findItem(R.id.nav_startstop_patrolling)?.isVisible = !AppState.isGuidedTrip
                    menuItem.title = if (AppState.isGuidedTrip) "Stop guided trip" else "Start guided trip"
                    navController.navigate(R.id.nav_home)
                    true
                }

                R.id.nav_startstop_patrolling -> {
                    AppState.isPatrolling = !AppState.isPatrolling
                    navView.menu.findItem(R.id.nav_startstop_guided_trip)?.isVisible = !AppState.isPatrolling
                    updatePatrolState(this,AppState.isPatrolling)
                    menuItem.title = if (AppState.isPatrolling) "Stop patrolling" else "Start patrolling"
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

        val serviceIntent = Intent(this, LocationService::class.java)
        startForegroundService(serviceIntent)
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

//    private suspend fun onStartStopPatrolling() {
//        var startstop = if (AppState.isPatrolling) "Start" else "Stop"
//
//        val vigilanteData = vigilanteStore.fetch()
//        if (vigilanteData == null) {
//            Log.e("Room", "Failed to fetch Vigilante")
//            return
//        }
//
//        val location = PatrolData(
//            id = vigilanteData.id,
//            vigilanteId = vigilanteData.id,
//            IsActive = AppState.isPatrolling,
//            name = vigilanteData.name
//        )
//
//        LocationUtils.getLocation(this) { currentLatLng ->
//            currentLatLng?.let {
//                location.location =
//                    mapOf("latitude" to it.latitude, "longitude" to it.longitude)
//                Toast.makeText(this, "${startstop} patrolling ${vigilanteData.name}...", Toast.LENGTH_SHORT).show()
//                sendPatrollingNotification(location)
//            } ?: run {
//                Toast.makeText(this, "${startstop} patrolling ${vigilanteData.name} failed...", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }

//    private fun sendPatrollingNotification(location: PatrolData) {
//        database.child("vigilanteLoc").child(location.id).setValue(location).addOnSuccessListener {
//            Log.e("Firebase", "Success saving distress:")
//        }
//            .addOnFailureListener { e ->
//                Log.e("Firebase", "Error saving distress: ${e.message}")
//            }
//    }
}