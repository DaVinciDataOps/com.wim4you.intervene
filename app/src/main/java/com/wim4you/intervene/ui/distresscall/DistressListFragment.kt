package com.wim4you.intervene.ui.distresscall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.R
import com.wim4you.intervene.data.DistressCallData
import com.wim4you.intervene.databinding.FragmentDistressListBinding
import com.wim4you.intervene.location.LocationUtils
import com.wim4you.intervene.repository.VigilanteDataRepository
import com.wim4you.intervene.ui.map.MapDataViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DistressListFragment : Fragment() {

    @Inject lateinit var vigilanteStore: VigilanteDataRepository

    private val mapDataViewModel: MapDataViewModel by activityViewModels()
    private val listViewModel: DistressListViewModel by viewModels()
    private lateinit var adapter: DistressCallAdapter
    private var _binding: FragmentDistressListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDistressListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DistressCallAdapter(
            isVerified = { distressId -> mapDataViewModel.isDistressVerified(distressId) },
            isIntervening = { distressId -> mapDataViewModel.isIntervening(distressId) },
            onItemClick = { item -> handleDistressCallSelected(item.call) },
            onRespondClick = { item -> handleDistressCallSelected(item.call) },
        )
        binding.recyclerViewDistressCalls.adapter = adapter
        binding.recyclerViewDistressCalls.layoutManager = LinearLayoutManager(context)

        binding.swipeRefresh.setOnRefreshListener { refreshList() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    listViewModel.distressItems.collectLatest { items ->
                        adapter.updateItems(items)
                        binding.emptyDistressList.isVisible = items.isEmpty()
                        binding.recyclerViewDistressCalls.isVisible = items.isNotEmpty()
                    }
                }
                launch {
                    listViewModel.isRefreshing.collectLatest { refreshing ->
                        binding.swipeRefresh.isRefreshing = refreshing
                    }
                }
                launch {
                    mapDataViewModel.verifiedDistressIds.collectLatest {
                        adapter.notifyDataSetChanged()
                    }
                }
                launch {
                    mapDataViewModel.interveningDistressIds.collectLatest {
                        adapter.notifyDataSetChanged()
                    }
                }
                launch {
                    mapDataViewModel.interventionMessage.collectLatest { messageKey ->
                        when (messageKey) {
                            "safe_word_incorrect" -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.safe_word_incorrect,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                mapDataViewModel.clearInterventionMessage()
                            }
                            "intervention_failed" -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.intervention_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                mapDataViewModel.clearInterventionMessage()
                            }
                        }
                    }
                }
            }
        }

        refreshList()
    }

    private fun refreshList() {
        LocationUtils.getLocation(requireContext()) { latLng ->
            listViewModel.updateUserLocation(latLng?.latitude, latLng?.longitude)
            listViewModel.refresh()
        }
    }

    private fun handleDistressCallSelected(distressCall: DistressCallData) {
        if (!AppModeController.isPatrolling) {
            Toast.makeText(requireContext(), R.string.patrol_required_to_intervene, Toast.LENGTH_LONG).show()
            return
        }

        val distressId = distressCall.id
        if (distressId == null) {
            Toast.makeText(requireContext(), R.string.intervention_failed, Toast.LENGTH_SHORT).show()
            return
        }

        if (mapDataViewModel.isDistressVerified(distressId)) {
            mapDataViewModel.focusDistress(distressId)
            findNavController().navigate(R.id.nav_home)
            return
        }

        SafeWordDialog.show(
            context = requireContext(),
            title = getString(R.string.safe_word_dialog_title),
            message = getString(R.string.safe_word_dialog_message, distressCall.alias.orEmpty()),
        ) { safeWord ->
            if (safeWord.isBlank()) {
                Toast.makeText(requireContext(), R.string.safe_word_required, Toast.LENGTH_SHORT).show()
                return@show
            }
            lifecycleScope.launch {
                val vigilante = AppModeController.vigilante ?: vigilanteStore.fetch()
                if (vigilante == null) {
                    Toast.makeText(requireContext(), R.string.register_vigilante_before_patrol, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AppModeController.vigilante = vigilante
                mapDataViewModel.verifyAndIntervene(
                    distressCall = distressCall,
                    safeWord = safeWord,
                    vigilante = vigilante,
                ) {
                    findNavController().navigate(R.id.nav_home)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.intervention_registered, distressCall.alias.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
