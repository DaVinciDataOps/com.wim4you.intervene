package com.wim4you.intervene.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.AppPreferences
import com.wim4you.intervene.MainActivity
import com.wim4you.intervene.OnLocationPermissionGrantedListener
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentHomeBinding
import com.wim4you.intervene.fbdata.DistressLocationData
import com.wim4you.intervene.helpers.GoogleMapMarkers
import com.wim4you.intervene.helpers.NetworkUtils
import com.wim4you.intervene.helpers.TimestampConverter
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.ui.map.MapDataViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment(), OnMapReadyCallback, OnLocationPermissionGrantedListener {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private val mapDataViewModel: MapDataViewModel by activityViewModels()

    private var mapOverlay: HomeMapOverlay? = null
    private var mMap: GoogleMap? = null
    private var mMapInitialized = false
    private var isDestinationPanelOpen = false
    private var hasCenteredOnUser = false
    private lateinit var destinationSuggestionAdapter: DestinationSuggestionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
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
        setupDestinationInput()
        setupClickListeners()
        observeViewModel()
        observeMapData()

        (requireActivity() as MainActivity).addLocationPermissionListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (!AppModeController.isGuidedTrip) {
            isDestinationPanelOpen = false
            viewModel.clearRoute()
            mapOverlay?.clearRoute()
        } else {
            restoreRouteOnMap()
        }
        updateGuidedTripUi()
        updateStatusBanner()
        centerMapOnUserIfNeeded()
    }

    override fun onForegroundLocationGranted() {
        centerMapOnUserIfNeeded()
        updateStatusBanner()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.removeLocationPermissionListener(this)
        mapOverlay = null
        mMap = null
        mMapInitialized = false
        super.onDestroyView()
        _binding = null
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mapOverlay = HomeMapOverlay(requireContext(), googleMap)
        mapOverlay?.applyMapStyle()
        mMapInitialized = true
        centerMapOnUserIfNeeded()

        val currentRoute = viewModel.routeState.value
        if (currentRoute is RouteState.Success) {
            mapOverlay?.drawRoute(currentRoute)
        }
    }

    private fun setupClickListeners() {
        binding.panicButton.setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (!hasLocationPermission()) {
                Toast.makeText(requireContext(), R.string.error_no_location_permission, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.onPanicButtonClicked(requireActivity())
        }
        binding.showRouteButton.setOnClickListener { requestRoute() }
        binding.addDestinationButton.setOnClickListener { openDestinationPanel() }
        binding.closeDestinationButton.setOnClickListener { closeDestinationPanel() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.distressMessage.collectLatest { message ->
                        message?.let {
                            Toast.makeText(requireContext(), it.resolve(requireContext()), Toast.LENGTH_SHORT).show()
                            viewModel.clearDistressMessage()
                        }
                    }
                }
                launch {
                    viewModel.panicButtonState.collectLatest { state ->
                        binding.panicProgressText.isVisible = state.isActive
                        if (state.isActive) {
                            binding.panicProgressText.text = getString(
                                R.string.panic_press_more,
                                state.pressesRemaining,
                            )
                        }
                    }
                }
                launch {
                    viewModel.routeState.collectLatest { state ->
                        renderRouteState(state)
                    }
                }
                launch {
                    viewModel.destinationSuggestions.collectLatest { suggestions ->
                        destinationSuggestionAdapter.submitSuggestions(suggestions)
                        if (binding.destinationInput.hasFocus() && suggestions.isNotEmpty()) {
                            binding.destinationInput.showDropDown()
                        }
                    }
                }
            }
        }
    }

    private fun observeMapData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mapDataViewModel.patrolLocations.collectLatest { patrolDataList ->
                        mapOverlay?.updatePatrolMarkers(patrolDataList)
                    }
                }
                launch {
                    mapDataViewModel.distressLocations.collectLatest { distressDataList ->
                        val selectedId = mapDataViewModel.selectedDistressId.value
                        val hasNewMarkers = mapOverlay?.updateDistressMarkers(distressDataList, selectedId) == true
                        populateSnackBar(distressDataList)
                        if (
                            AppModeController.isPatrolling &&
                            hasNewMarkers &&
                            AppPreferences.isPatrolAlertSoundEnabled(requireContext())
                        ) {
                            PatrolAlertSoundPlayer.play(requireContext())
                        }
                    }
                }
                launch {
                    mapDataViewModel.selectedDistressId.collectLatest { selectedId ->
                        if (selectedId == null) return@collectLatest
                        val distress = mapDataViewModel.distressLocations.value
                            .firstOrNull { (it.id ?: it.personId) == selectedId }
                        val lat = distress?.latitude
                        val lng = distress?.longitude
                        if (lat != null && lng != null && mMapInitialized) {
                            mapOverlay?.focusOnDistress(selectedId, lat, lng)
                            mapOverlay?.updateDistressMarkers(
                                mapDataViewModel.distressLocations.value,
                                selectedId,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun renderRouteState(state: RouteState) {
        when (state) {
            RouteState.Idle -> {
                binding.showRouteButton.isEnabled = true
                binding.routeSummary.visibility = View.GONE
                if (!AppModeController.isGuidedTrip) {
                    mapOverlay?.clearRoute()
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
                    mapOverlay?.drawRoute(state)
                }
                isDestinationPanelOpen = false
                updateGuidedTripUi()
            }
            is RouteState.Error -> {
                binding.showRouteButton.isEnabled = true
                binding.routeSummary.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    state.message.resolve(requireContext()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun updateStatusBanner() {
        val context = requireContext()
        val message = when {
            !NetworkUtils.isOnline(context) -> getString(R.string.status_offline)
            !hasLocationPermission() -> getString(R.string.status_no_location_permission)
            else -> null
        }
        binding.statusBanner.isVisible = message != null
        binding.statusBannerText.text = message
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun centerMapOnUserIfNeeded() {
        val map = mMap ?: return
        if (!mMapInitialized || hasCenteredOnUser || !hasLocationPermission()) return

        map.isMyLocationEnabled = true
        LocationUtils.getLocation(requireContext()) { currentLatLng ->
            currentLatLng?.let {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
                hasCenteredOnUser = true
            }
        }
    }

    private fun restoreRouteOnMap() {
        val currentRoute = viewModel.routeState.value
        if (currentRoute is RouteState.Success && mMapInitialized) {
            mapOverlay?.drawRoute(currentRoute)
        }
    }

    private fun updateGuidedTripUi() {
        val isGuidedTrip = AppModeController.isGuidedTrip
        binding.panicButton.visibility = if (isGuidedTrip) View.VISIBLE else View.GONE
        binding.panicProgressText.isVisible = isGuidedTrip && viewModel.panicButtonState.value.isActive

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
            viewModel.planRoute(
                destination,
                origin,
                NetworkUtils.isOnline(requireContext()),
            )
        }
    }

    private fun populateSnackBar(list: List<DistressLocationData>) {
        AppModeController.snackBarMessage = if (list.isEmpty()) {
            getString(R.string.snackbar_no_distress_calls)
        } else {
            list.take(5).joinToString("\n------------\n") { call ->
                getString(
                    R.string.snackbar_distress_entry,
                    call.alias.orEmpty(),
                    call.address ?: getString(R.string.distress_address_unknown),
                    TimestampConverter.toTime(call.startTime),
                )
            }
        }
    }
}
