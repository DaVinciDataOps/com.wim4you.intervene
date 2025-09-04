package com.wim4you.intervene.ui.home
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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

    private val markers = mutableMapOf<String, Marker>()

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

        viewModel.patrolLocations.observe(viewLifecycleOwner) { patrolDataList ->
            updatePatrolMapMarkers(patrolDataList)
        }
        viewModel.distressLocations.observe(viewLifecycleOwner) { distressDataList ->
            updateDistressMapMarkers(distressDataList)
        }

        viewModel.registerLocationReceiver(requireContext())
        viewModel.startLocationService(requireContext())

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
    }
    private fun updatePatrolMapMarkers(patrolDataList: List<PatrolData>){
        val currentIds = patrolDataList.map { it.id }.toSet()
        markers.keys.filter { it !in currentIds }.forEach { id ->
            markers[id]?.remove()
            markers.remove(id)
        }

        // Add or update markers for active patrols
        patrolDataList.forEach { patrolData ->
            patrolData.location?.let { loc ->
                val latLng = LatLng(loc["latitude"] ?: 0.0, loc["longitude"] ?: 0.0)
                val marker = markers[patrolData.id]
                if (marker == null) {
                    // Add new marker
                    val newMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title(patrolData.name ?: "Vigilante")
                            .icon(getIcon(patrolData.vigilanteId))
                    )
                    if (newMarker != null) {
                        markers[patrolData.id] = newMarker
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
}