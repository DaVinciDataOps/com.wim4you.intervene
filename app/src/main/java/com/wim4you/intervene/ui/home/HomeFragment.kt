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

import android.view.inputmethod.EditorInfo

import android.widget.Toast

import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

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

import com.google.android.gms.maps.model.LatLngBounds

import com.google.android.gms.maps.model.MapStyleOptions

import com.google.android.gms.maps.model.Marker

import com.google.android.gms.maps.model.MarkerOptions

import com.google.android.gms.maps.model.Polyline

import com.google.android.gms.maps.model.PolylineOptions

import com.wim4you.intervene.AppModeController

import com.wim4you.intervene.R
import com.wim4you.intervene.ThemePreferences

import com.wim4you.intervene.dao.DatabaseProvider

import com.wim4you.intervene.databinding.FragmentHomeBinding

import com.wim4you.intervene.fbdata.DistressLocationData

import com.wim4you.intervene.fbdata.PatrolLocationData

import com.wim4you.intervene.helpers.GoogleMapMarkers

import com.wim4you.intervene.helpers.TimestampConverter

import com.wim4you.intervene.location.LocationUtils

import com.wim4you.intervene.repository.DestinationHistoryRepository
import com.wim4you.intervene.repository.PersonDataRepository

import com.wim4you.intervene.route.RouteRepository

import com.wim4you.intervene.ui.map.MapDataViewModel



class HomeFragment : Fragment(), OnMapReadyCallback {



    private var _binding: FragmentHomeBinding? = null

    private val binding get() = _binding!!



    private val viewModel: HomeViewModel by viewModels {
        val database = DatabaseProvider.getDatabase(requireContext())
        HomeViewModelFactory(
            PersonDataRepository(database.personDataDao()),
            RouteRepository(requireContext().applicationContext),
            DestinationHistoryRepository(database.destinationHistoryDao()),
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

    private var routePolyline: Polyline? = null
    private var destinationMarker: Marker? = null
    private var isDestinationPanelOpen = false
    private lateinit var destinationSuggestionAdapter: DestinationSuggestionAdapter



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



        updateGuidedTripUi()



        binding.panicButton.setOnClickListener {

            if (ContextCompat.checkSelfPermission(

                    requireContext(),

                    Manifest.permission.ACCESS_FINE_LOCATION

                ) == PackageManager.PERMISSION_GRANTED

            ) {

                viewModel.onPanicButtonClicked(requireActivity())

            }

        }



        binding.showRouteButton.setOnClickListener { requestRoute() }
        binding.addDestinationButton.setOnClickListener { openDestinationPanel() }
        binding.closeDestinationButton.setOnClickListener { closeDestinationPanel() }
        setupDestinationInput()



        viewModel.distressStatus.observe(viewLifecycleOwner) { status ->

            status?.let {

                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()

            }

        }



        viewModel.routeState.observe(viewLifecycleOwner) { state ->

            when (state) {

                RouteState.Idle -> {
                    binding.showRouteButton.isEnabled = true
                    binding.routeSummary.visibility = View.GONE
                    if (!AppModeController.isGuidedTrip) {
                        clearRouteFromMap()
                    }
                }

                RouteState.Loading -> {

                    binding.showRouteButton.isEnabled = false

                    binding.routeSummary.visibility = View.VISIBLE

                    binding.routeSummary.text = getString(R.string.route_loading)

                }

                is RouteState.Success -> {
                    binding.showRouteButton.isEnabled = true
                    binding.routeSummary.visibility = View.VISIBLE
                    binding.routeSummary.text = state.summary
                    if (mMapInitialized) {
                        drawRoute(state)
                    }
                    isDestinationPanelOpen = false
                    updateGuidedTripUi()
                }

                is RouteState.Error -> {

                    binding.showRouteButton.isEnabled = true

                    binding.routeSummary.visibility = View.GONE

                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()

                }

            }

        }

        viewModel.destinationSuggestions.observe(viewLifecycleOwner) { suggestions ->
            destinationSuggestionAdapter.submitSuggestions(suggestions)
            if (binding.destinationInput.hasFocus() && suggestions.isNotEmpty()) {
                binding.destinationInput.showDropDown()
            }
        }

    }



    override fun onResume() {
        super.onResume()
        if (!AppModeController.isGuidedTrip) {
            isDestinationPanelOpen = false
            viewModel.clearRoute()
            clearRouteFromMap()
        } else {
            restoreRouteOnMap()
        }
        updateGuidedTripUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }



    override fun onMapReady(googleMap: GoogleMap) {

        mMap = googleMap

        applyMapStyle()

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



        val currentRoute = viewModel.routeState.value
        if (currentRoute is RouteState.Success) {
            drawRoute(currentRoute)
        }
    }

    private fun applyMapStyle() {
        if (!ThemePreferences.isDarkModeActive(requireContext())) return
        MapStyleOptions.loadRawResourceStyle(requireContext(), R.raw.map_style_dark)
            .takeIf { it != null }
            ?.let { style ->
                mMap.setMapStyle(style)
            }
    }

    private fun restoreRouteOnMap() {
        val currentRoute = viewModel.routeState.value
        if (currentRoute is RouteState.Success && mMapInitialized) {
            drawRoute(currentRoute)
        }
    }



    private fun updateGuidedTripUi() {
        val isGuidedTrip = AppModeController.isGuidedTrip
        binding.panicButton.visibility = if (isGuidedTrip) View.VISIBLE else View.GONE

        if (!isGuidedTrip) {
            binding.routeCard.visibility = View.GONE
            binding.addDestinationButton.visibility = View.GONE
            return
        }

        binding.routeCard.visibility = if (isDestinationPanelOpen) View.VISIBLE else View.GONE
        binding.addDestinationButton.visibility = if (isDestinationPanelOpen) View.GONE else View.VISIBLE
    }

    private fun openDestinationPanel() {
        isDestinationPanelOpen = true
        val currentRoute = viewModel.routeState.value
        if (currentRoute is RouteState.Success) {
            binding.routeSummary.visibility = View.VISIBLE
            binding.routeSummary.text = currentRoute.summary
        }
        updateGuidedTripUi()
        viewModel.loadDestinationSuggestions(binding.destinationInput.text?.toString().orEmpty())
        binding.destinationInput.post {
            binding.destinationInput.requestFocus()
            binding.destinationInput.showDropDown()
        }
    }

    private fun closeDestinationPanel() {
        isDestinationPanelOpen = false
        updateGuidedTripUi()
    }

    private fun setupDestinationInput() {
        destinationSuggestionAdapter = DestinationSuggestionAdapter(requireContext())
        binding.destinationInput.setAdapter(destinationSuggestionAdapter)
        binding.destinationInput.threshold = 0

        binding.destinationInput.doAfterTextChanged { editable ->
            viewModel.loadDestinationSuggestions(editable?.toString().orEmpty())
        }

        binding.destinationInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                viewModel.loadDestinationSuggestions(binding.destinationInput.text?.toString().orEmpty())
            }
        }

        binding.destinationInput.setOnItemClickListener { _, _, position, _ ->
            val suggestion = destinationSuggestionAdapter.getItem(position) ?: return@setOnItemClickListener
            binding.destinationInput.setText(suggestion.address)
            binding.destinationInput.setSelection(suggestion.address.length)
        }

        binding.destinationInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                requestRoute()
                true
            } else {
                false
            }
        }
    }



    private fun requestRoute() {

        val destination = binding.destinationInput.text?.toString().orEmpty()

        if (destination.isBlank()) {

            Toast.makeText(requireContext(), R.string.route_enter_destination, Toast.LENGTH_SHORT).show()

            return

        }



        LocationUtils.setLocation(requireContext()) { origin ->

            if (origin == null) {

                Toast.makeText(requireContext(), R.string.route_location_unavailable, Toast.LENGTH_SHORT).show()

                return@setLocation

            }

            viewModel.planRoute(destination, origin)

        }

    }



    private fun drawRoute(state: RouteState.Success) {

        clearRouteFromMap()



        routePolyline = mMap.addPolyline(

            PolylineOptions()

                .addAll(state.points)

                .color(ContextCompat.getColor(requireContext(), R.color.route_polyline))

                .width(12f)

        )



        destinationMarker = mMap.addMarker(

            MarkerOptions()

                .position(state.destination)

                .title(getString(R.string.route_destination_marker))

        )



        val boundsBuilder = LatLngBounds.Builder()

        state.points.forEach { boundsBuilder.include(it) }

        val bounds = boundsBuilder.build()

        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))

    }



    private fun clearRouteFromMap() {

        routePolyline?.remove()

        routePolyline = null

        destinationMarker?.remove()

        destinationMarker = null

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


