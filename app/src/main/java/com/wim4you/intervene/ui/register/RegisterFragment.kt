package com.wim4you.intervene.ui.register

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
import com.wim4you.intervene.R
import com.wim4you.intervene.data.PersonData
import com.wim4you.intervene.databinding.FragmentRegisterBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class RegisterFragment : Fragment() {
    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RegisterViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentPerson.collectLatest { person ->
                    person?.let {
                        val genderOptions = resources.getStringArray(R.array.gender_options)
                        val genderPosition = genderOptions.indexOf(it.gender)
                        binding.nameInputEditText.setText(it.name)
                        binding.aliasInputEditText.setText(it.alias)
                        binding.genderAutoCompleteTextView.setSelection(genderPosition)
                        binding.ageInputEditText.setText(it.age.toString())
                        binding.phoneNumberInputEditText.setText(it.phoneNumber)
                        binding.safeWordInputEditText.setText(it.safeWord)
                        binding.emailInputEditText.setText(it.eMail)
                    }
                }
            }
        }

        viewModel.fetchPersonData()

        binding.saveButton.setOnClickListener {
            val name = binding.nameInputEditText.text.toString().trim()
            val alias = binding.aliasInputEditText.text.toString().trim()
            val gender = binding.genderAutoCompleteTextView.selectedItem as? String ?: ""
            val age = binding.ageInputEditText.text.toString().trim()
            val phoneNumber = binding.phoneNumberInputEditText.text.toString().trim()
            val safeWord = binding.safeWordInputEditText.text.toString().trim()
            val email = binding.emailInputEditText.text.toString().trim()

            if (name.isEmpty() || alias.isEmpty() || gender.isEmpty() || age.isEmpty() ||
                phoneNumber.isEmpty() || safeWord.isEmpty()
            ) {
                Toast.makeText(requireContext(), R.string.form_fill_required_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val personData = PersonData(
                id = viewModel.recentPerson.value?.id ?: UUID.randomUUID().toString(),
                name = name,
                alias = alias,
                gender = gender,
                age = age.toIntOrNull() ?: 0,
                phoneNumber = phoneNumber,
                safeWord = safeWord,
                eMail = email,
            )
            viewModel.savePersonData(personData)
            Toast.makeText(requireContext(), R.string.form_data_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
