package com.wim4you.intervene.ui.home
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.wim4you.intervene.AppState
import com.wim4you.intervene.R
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.databinding.FragmentHomeBinding
import com.wim4you.intervene.fbdata.PatrolData
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository
import androidx.core.graphics.scale
import com.wim4you.intervene.fbdata.DistressLocationData

class HomeFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            PersonDataRepository(DatabaseProvider.getDatabase(requireContext()).personDataDao()),
            VigilanteDataRepository(DatabaseProvider.getDatabase(requireContext()).vigilanteDataDao())
        )
    }

    //private val viewModel: HomeViewModel by viewModels()
    // private lateinit var viewModel: HomeViewModel

    private lateinit var mMap: GoogleMap
    private lateinit var patrolMarker:Bitmap
    private lateinit var myPatrolMarker:Bitmap
    private lateinit var distressMarker:Bitmap

    private val patrolMarkers = mutableMapOf<String, Marker>()
    private val distressMarkers = mutableMapOf<String, Marker>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapFragment =
            childFragmentManager.findFragmentById(binding.googleMap.id) as SupportMapFragment
        mapFragment.getMapAsync(this)

        patrolMarker = BitmapFactory.decodeResource(resources, R.drawable.png_patrol_marker)
            .scale(64, 64, false)
        myPatrolMarker = BitmapFactory.decodeResource(resources, R.drawable.png_my_patrol_marker)
            .scale(64, 64, false)

        distressMarker = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher_round)
            .scale(90, 90, false)

        viewModel.registerLocationReceiver(requireContext())
        viewModel.startLocationService(requireContext())

        viewModel.patrolLocations.observe(viewLifecycleOwner) { patrolDataList ->
            updatePatrolMapMarkers(patrolDataList)
        }
        viewModel.distressLocations.observe(viewLifecycleOwner) { distressDataList ->
            updateDistressMapMarkers(distressDataList)
        }

        val personStore =
            PersonDataRepository(DatabaseProvider.getDatabase(requireContext()).personDataDao())

        val vigilanteStore =
            VigilanteDataRepository(DatabaseProvider.getDatabase(requireContext()).vigilanteDataDao())

//        viewModel = ViewModelProvider(this, HomeViewModelFactory(personStore,vigilanteStore))
//            .get(HomeViewModel::class.java)

        if(AppState.isGuidedTrip){
            binding.panicButton.visibility = View.VISIBLE
        }
        else {
            binding.panicButton.visibility = View.GONE
        }

        binding.panicButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.onPanicButtonClicked(requireActivity())
            }
        }

        viewModel.distressStatus.observe(viewLifecycleOwner) { status ->
            status?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopLocationService(requireContext())
        _binding = null
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            mMap.isMyLocationEnabled = true

            LocationUtils.getLocation(requireContext()) { currentLatLng ->
                currentLatLng?.let {
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                }
            }
        }
    }

    private fun updateDistressMapMarkers(distressDataList: List<DistressLocationData>){
        val currentIds = distressDataList.map { it.id }.toSet()
        var hasNewMarkers = false;
        distressMarkers.keys.filter { it !in currentIds }.forEach { id ->
            distressMarkers[id]?.remove()
            distressMarkers.remove(id)
        }

        // Add or update markers for active patrols
        distressDataList.forEach { distressData ->
            distressData.location?.let { loc ->
                val latLng = LatLng(loc["latitude"] ?: 0.0, loc["longitude"] ?: 0.0)
                val marker = distressMarkers[distressData.id]
                if (marker == null) {
                    // Add new marker
                    val newMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("!HELP!")
                            .icon(BitmapDescriptorFactory.fromBitmap(distressMarker))
                    )
                    if (newMarker != null) {
                        distressMarkers[distressData.id] = newMarker
                    }
                    hasNewMarkers = true
                } else {
                    // Update existing marker position
                    marker.position = latLng
                }
            }
        }

        if(AppState.isPatrolling && hasNewMarkers)
            playPatrolDistressSound()
    }
    private fun updatePatrolMapMarkers(patrolDataList: List<PatrolData>){
        val currentIds = patrolDataList.map { it.id }.toSet()
        patrolMarkers.keys.filter { it !in currentIds }.forEach { id ->
            patrolMarkers[id]?.remove()
            patrolMarkers.remove(id)
        }

        // Add or update markers for active patrols
        patrolDataList.forEach { patrolData ->
            patrolData.location?.let { loc ->
                val latLng = LatLng(loc["latitude"] ?: 0.0, loc["longitude"] ?: 0.0)
                val marker = patrolMarkers[patrolData.id]
                if (marker == null) {
                    // Add new marker
                    val newMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title(patrolData.name ?: "Vigilante")
                            .icon(getIcon(patrolData.vigilanteId))
                    )
                    if (newMarker != null) {
                        patrolMarkers[patrolData.id] = newMarker
                    }
                } else {
                    // Update existing marker position
                    marker.position = latLng
                }
            }
        }

    }

    private fun getIcon(vigilanteId: String?): BitmapDescriptor {
        // Handle null cases safely
        var id = AppState.vigilante?.id
        return if (vigilanteId != null && AppState.vigilante?.id != null && vigilanteId == id) {
            BitmapDescriptorFactory.fromBitmap(myPatrolMarker)
        } else {
            BitmapDescriptorFactory.fromBitmap(patrolMarker)
        }
    }

    private fun playPatrolDistressSound() {
        val mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
        mediaPlayer?.let { player ->
            try {
                // Set volume to maximum
                val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    0
                )
                // Start playing the sound
                player.isLooping = false
                player.start()
                // Stop after 10 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    player.stop()
                    player.release()
                }, 10000) // 10 seconds
            } catch (e: Exception) {
                e.printStackTrace()
                player.release()
            }
        }
    }
}