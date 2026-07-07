package com.wim4you.intervene.ui.home
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.fragment.app.activityViewModels
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
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.databinding.FragmentHomeBinding
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.fbdata.PatrolLocationData
import com.wim4you.intervene.helpers.GoogleMapMarkers
import com.wim4you.intervene.helpers.TimestampConverter
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.ui.map.MapDataViewModel

class HomeFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            PersonDataRepository(DatabaseProvider.getDatabase(requireContext()).personDataDao()),
        )
    }
    private val mapDataViewModel: MapDataViewModel by activityViewModels()

    private lateinit var mMap: GoogleMap
    private var mMapInitialized = false
    private lateinit var patrolMarker: Bitmap
    private lateinit var myPatrolMarker: Bitmap
    private lateinit var distressMarker: Bitmap
    private val patrolMarkers = mutableMapOf<String, Marker>()
    private val distressMarkers = mutableMapOf<String, Marker>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapFragment =
            childFragmentManager.findFragmentById(binding.googleMap.id) as SupportMapFragment
        mapFragment.getMapAsync(this)

        GoogleMapMarkers.initialize(requireContext())
        patrolMarker = GoogleMapMarkers.patrolMarker
        myPatrolMarker = GoogleMapMarkers.myPatrolMarker
        distressMarker = GoogleMapMarkers.distressMarker

        mapDataViewModel.patrolLocations.observe(viewLifecycleOwner) { patrolDataList ->
            updatePatrolMapMarkers(patrolDataList)
        }
        mapDataViewModel.distressLocations.observe(viewLifecycleOwner) { distressDataList ->
            updateDistressMapMarkers(distressDataList)
            populateSnackBar(distressDataList)
        }

        binding.panicButton.visibility = if (AppModeController.isGuidedTrip) View.VISIBLE else View.GONE

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

        mMapInitialized = true
    }

    private fun updateDistressMapMarkers(distressDataList: List<DistressLocationData>) {
        if (!mMapInitialized) return

        val currentIds = distressDataList.mapNotNull { it.id }.toSet()
        var hasNewMarkers = false
        distressMarkers.keys.filter { it !in currentIds }.forEach { id ->
            distressMarkers[id]?.remove()
            distressMarkers.remove(id)
        }

        distressDataList.forEach { distressData ->
            distressData.l?.let { loc ->
                val latLng = LatLng(loc[0], loc[1])
                val markerId = distressData.id ?: return@let
                val marker = distressMarkers[markerId]
                if (marker == null) {
                    val newMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("${distressData.alias} !HELP!")
                            .snippet(getSnippetString(distressData, System.currentTimeMillis(), distressData.startTime))
                            .icon(BitmapDescriptorFactory.fromBitmap(distressMarker))
                    )
                    if (newMarker != null) {
                        distressMarkers[markerId] = newMarker
                    }
                    hasNewMarkers = true
                } else {
                    marker.position = latLng
                    marker.snippet = getSnippetString(distressData, System.currentTimeMillis(), distressData.startTime)
                }
            }
        }

        if (AppModeController.isPatrolling && hasNewMarkers) {
            playPatrolDistressSound()
        }
    }

    private fun getSnippetString(
        distressData: DistressLocationData,
        currentTimestamp: Long?,
        startTimestamp: Long?
    ): String {
        val lap = TimestampConverter.lapSeconds(startTimestamp, currentTimestamp).toString()
        val start = TimestampConverter.toTime(startTimestamp)
        return "time:$start [lap:${lap} sec]"
    }

    private fun updatePatrolMapMarkers(patrolLocationDataList: List<PatrolLocationData>) {
        if (!mMapInitialized) return

        val currentIds = patrolLocationDataList.mapNotNull { it.id }.toSet()
        patrolMarkers.keys.filter { it !in currentIds }.forEach { id ->
            patrolMarkers[id]?.remove()
            patrolMarkers.remove(id)
        }

        patrolLocationDataList.forEach { patrolData ->
            patrolData.l?.let { loc ->
                val latLng = LatLng(loc[0], loc[1])
                val markerId = patrolData.id ?: return@let
                val marker = patrolMarkers[markerId]
                if (marker == null) {
                    val newMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title(patrolData.name ?: "Patrol")
                            .icon(getIcon(patrolData.vigilanteId))
                    )
                    if (newMarker != null) {
                        patrolMarkers[markerId] = newMarker
                    }
                } else {
                    marker.position = latLng
                }
            }
        }
    }

    private fun getIcon(vigilanteId: String?): BitmapDescriptor {
        val myId = AppModeController.vigilante?.id
        return if (vigilanteId != null && myId != null && vigilanteId == myId) {
            BitmapDescriptorFactory.fromBitmap(myPatrolMarker)
        } else {
            BitmapDescriptorFactory.fromBitmap(patrolMarker)
        }
    }

    private fun populateSnackBar(list: List<DistressLocationData>) {
        val message = if (list.isEmpty()) {
            "No Distress calls"
        } else {
            list.take(5).joinToString("\n------------\n") { call ->
                "${call.alias}: ${call.address ?: "Unknown address"} at ${TimestampConverter.toTime(call.startTime)}"
            }
        }
        AppModeController.snackBarMessage = message
    }

    private fun playPatrolDistressSound() {
        val mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
        mediaPlayer?.let { player ->
            try {
                val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    0
                )
                player.isLooping = false
                player.start()
                Handler(Looper.getMainLooper()).postDelayed({
                    player.stop()
                    player.release()
                }, 10000)
            } catch (e: Exception) {
                e.printStackTrace()
                player.release()
            }
        }
    }
}
