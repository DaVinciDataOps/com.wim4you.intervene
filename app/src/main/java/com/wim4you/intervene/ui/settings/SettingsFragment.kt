package com.wim4you.intervene.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.wim4you.intervene.AppPreferences
import com.wim4you.intervene.R
import com.wim4you.intervene.ThemePreferences
import com.wim4you.intervene.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.settingsVersion.text = getString(
            R.string.menu_version,
            getString(R.string.app_build_number),
        )

        bindThemeSwitches()
        bindNotificationSwitches()
    }

    private fun bindThemeSwitches() {
        binding.switchDarkMode.setOnCheckedChangeListener(null)
        binding.switchDarkMode.isChecked = ThemePreferences.isDarkModeActive(requireContext())
        binding.switchFollowSystem.setOnCheckedChangeListener(null)
        binding.switchFollowSystem.isChecked =
            ThemePreferences.getMode(requireContext()) == ThemePreferences.Mode.SYSTEM

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) ThemePreferences.Mode.DARK else ThemePreferences.Mode.LIGHT
            if (ThemePreferences.getMode(requireContext()) != newMode) {
                ThemePreferences.setMode(requireContext(), newMode)
                refreshThemeSwitches()
            }
        }

        binding.switchFollowSystem.setOnCheckedChangeListener { _, isChecked ->
            val newMode = when {
                isChecked -> ThemePreferences.Mode.SYSTEM
                ThemePreferences.isDarkModeActive(requireContext()) -> ThemePreferences.Mode.DARK
                else -> ThemePreferences.Mode.LIGHT
            }
            if (ThemePreferences.getMode(requireContext()) != newMode) {
                ThemePreferences.setMode(requireContext(), newMode)
                refreshThemeSwitches()
            }
        }
    }

    private fun refreshThemeSwitches() {
        binding.switchDarkMode.setOnCheckedChangeListener(null)
        binding.switchDarkMode.isChecked = ThemePreferences.isDarkModeActive(requireContext())
        binding.switchFollowSystem.setOnCheckedChangeListener(null)
        binding.switchFollowSystem.isChecked =
            ThemePreferences.getMode(requireContext()) == ThemePreferences.Mode.SYSTEM
        bindThemeSwitches()
    }

    private fun bindNotificationSwitches() {
        binding.switchPatrolAlertSound.isChecked =
            AppPreferences.isPatrolAlertSoundEnabled(requireContext())
        binding.switchPatrolAlertSound.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setPatrolAlertSoundEnabled(requireContext(), isChecked)
        }

        binding.switchReadAloud.isChecked =
            AppPreferences.isReadAloudEnabled(requireContext())
        binding.switchReadAloud.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setReadAloudEnabled(requireContext(), isChecked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
