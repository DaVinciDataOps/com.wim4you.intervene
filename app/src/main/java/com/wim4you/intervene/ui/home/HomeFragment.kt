package com.wim4you.intervene.ui.home
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.wim4you.intervene.R
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.databinding.FragmentHomeBinding
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.repository.PersonDataRepository
import com.wim4you.intervene.repository.VigilanteDataRepository

class HomeFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    //private val viewModel: HomeViewModel by viewModels()
    private lateinit var viewModel: HomeViewModel

    private lateinit var mMap: GoogleMap

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
        // Initialize the ViewModel
        val personStore =
            PersonDataRepository(DatabaseProvider.getDatabase(requireContext()).personDataDao())

        val vigilanteStore =
            VigilanteDataRepository(DatabaseProvider.getDatabase(requireContext()).vigilanteDataDao())
        viewModel = ViewModelProvider(this, HomeViewModelFactory(personStore,vigilanteStore))
            .get(HomeViewModel::class.java)

        val mapFragment =
            childFragmentManager.findFragmentById(binding.googleMap.id) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.buttonStartGuidedTrip.setOnClickListener {
            binding.panicButton.visibility = View.VISIBLE
            binding.buttonStartGuidedTrip.visibility = View.GONE
            binding.buttonStartPatroling.visibility = View.GONE
        }

        binding.buttonStartPatroling.setOnClickListener {
            if(binding.buttonStartGuidedTrip.isGone) {
                binding.panicButton.visibility = View.GONE
                binding.buttonStartGuidedTrip.visibility = View.VISIBLE
                binding.buttonStartPatroling.setText(R.string.home_start_patrolling)
            }
            else {
                binding.panicButton.visibility = View.GONE
                binding.buttonStartGuidedTrip.visibility = View.GONE
                binding.buttonStartPatroling.setText(R.string.home_stop_patrolling)
            }

            viewModel.onStartPatrollingButtonClicked(requireActivity())
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
}