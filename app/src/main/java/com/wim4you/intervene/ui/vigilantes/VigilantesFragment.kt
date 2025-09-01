package com.wim4you.intervene.ui.vigilantes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.VigilanteData
import com.wim4you.intervene.databinding.FragmentVigilantesBinding
import com.wim4you.intervene.repository.VigilanteDataRepository
import java.util.UUID

class VigilantesFragment : Fragment() {
    private var _binding: FragmentVigilantesBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: VigilantesViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout using view binding
        _binding = FragmentVigilantesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the ViewModel
        val repository =
            VigilanteDataRepository(DatabaseProvider.getDatabase(requireContext()).vigilanteDataDao())
        viewModel = ViewModelProvider(this, VigilantesViewModelFactory(repository))
            .get(VigilantesViewModel::class.java)

        viewModel.recentData.observe(viewLifecycleOwner) { person ->
            person?.let {
                binding.groupNameInputEditText.setText(it.name)
                binding.groupSizeInputEditText.setText(it.groupSize.toString())
            }
        }
        viewModel.fetchData()

        // Set up save button click listener
        binding.saveButton.setOnClickListener {
            val groupName = binding.groupNameInputEditText.text.toString().trim()
            val groupSize = binding.groupSizeInputEditText.text.toString().trim()

            // Basic validation
            if (groupName.isEmpty() || groupSize.isEmpty() ) {
                Toast.makeText(requireContext(), "Please fill all required fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var vigilanteData = VigilanteData(
                id = viewModel.recentData.value?.id ?: UUID.randomUUID().toString(),
                name = groupName,
                groupSize = groupSize.toIntOrNull() ?: 0,
                isGroup = (groupSize.toIntOrNull() ?: 0) > 1,
                groupOwnerId = "",
                isCertifiedVigilante = false,
                isActive = true,
                ownerId = ""
                )
            // Call ViewModel to save data
            viewModel.saveData(vigilanteData)

            // Show success message
            Toast.makeText(requireContext(), "Data saved successfully", Toast.LENGTH_SHORT).show()

            // Optionally clear the form
            // clearForm()
        }
    }

    override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }
}