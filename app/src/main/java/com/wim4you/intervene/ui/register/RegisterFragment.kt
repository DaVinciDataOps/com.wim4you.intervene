package com.wim4you.intervene.ui.register

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.wim4you.intervene.dao.DatabaseProvider
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.databinding.FragmentRegisterBinding
import com.wim4you.intervene.repository.PersonDataRepository


class RegisterFragment: Fragment() {
    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: RegisterViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout using view binding
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the ViewModel
        val repository =
            PersonDataRepository(DatabaseProvider.getDatabase(requireContext()).personDataDao())
        viewModel = ViewModelProvider(this, RegisterViewModelFactory(repository))
            .get(RegisterViewModel::class.java)

        viewModel.recentPerson.observe(viewLifecycleOwner) { person ->
            person?.let {
                binding.nameInputEditText.setText(it.name)
                binding.aliasInputEditText.setText(it.alias)
                binding.genderInputEditText.setText(it.gender)
                binding.ageInputEditText.setText(it.age.toString())
                binding.phoneNumberInputEditText.setText(it.phoneNumber)
                binding.safeWordInputEditText.setText(it.safeWord)
                binding.emailInputEditText.setText(it.eMail)
            }
        }
        viewModel.fetchPersonData()

        // Set up save button click listener
        binding.saveButton.setOnClickListener {
            val id = it.id// Collect input data
            val name = binding.nameInputEditText.text.toString().trim()
            val alias = binding.aliasInputEditText.text.toString().trim()
            val gender = binding.genderInputEditText.text.toString().trim()
            val age = binding.ageInputEditText.text.toString().trim()
            val phoneNumber = binding.phoneNumberInputEditText.text.toString().trim()
            val safeWord = binding.safeWordInputEditText.text.toString().trim()
            val email = binding.emailInputEditText.text.toString().trim()

            // Basic validation
            if (name.isEmpty() || alias.isEmpty() || gender.isEmpty() || age.isEmpty() ||
                phoneNumber.isEmpty() || safeWord.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill all required fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var personData = PersonData(name = name,
                alias = alias,
                gender = gender,
                age = age.toIntOrNull() ?: 0,
                phoneNumber = phoneNumber,
                safeWord = safeWord,
                eMail = email)
            // Call ViewModel to save data
            viewModel.savePersonData(personData)

            // Show success message
            Toast.makeText(requireContext(), "Data saved successfully", Toast.LENGTH_SHORT).show()

            // Optionally clear the form
            // clearForm()
        }
    }

    private fun clearForm() {
        binding.nameInputEditText.text?.clear()
        binding.aliasInputEditText.text?.clear()
        binding.genderInputEditText.text?.clear()
        binding.ageInputEditText.text?.clear()
        binding.phoneNumberInputEditText.text?.clear()
        binding.safeWordInputEditText.text?.clear()
        binding.emailInputEditText.text?.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Avoid memory leaks
    }

}