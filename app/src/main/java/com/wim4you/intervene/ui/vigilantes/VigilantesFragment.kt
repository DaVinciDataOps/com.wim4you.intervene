package com.wim4you.intervene.ui.vigilantes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.R
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.databinding.FragmentVigilantesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class VigilantesFragment : Fragment() {
    private var _binding: FragmentVigilantesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VigilantesViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVigilantesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentData.collectLatest { person ->
                    person?.let {
                        binding.groupNameInputEditText.setText(it.name)
                        binding.groupSizeInputEditText.setText(it.groupSize.toString())
                    }
                }
            }
        }

        viewModel.fetchData()

        binding.saveButton.setOnClickListener {
            val groupName = binding.groupNameInputEditText.text.toString().trim()
            val groupSize = binding.groupSizeInputEditText.text.toString().trim()

            if (groupName.isEmpty() || groupSize.isEmpty()) {
                Toast.makeText(requireContext(), R.string.form_fill_required_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val vigilanteData = VigilanteData(
                id = viewModel.recentData.value?.id ?: UUID.randomUUID().toString(),
                name = groupName,
                groupSize = groupSize.toIntOrNull() ?: 0,
                isGroup = (groupSize.toIntOrNull() ?: 0) > 1,
                groupOwnerId = "",
                isCertifiedVigilante = false,
                isActive = true,
                ownerId = "",
            )
            viewModel.saveData(vigilanteData)
            AppModeController.vigilante = vigilanteData
            Toast.makeText(requireContext(), R.string.form_data_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
