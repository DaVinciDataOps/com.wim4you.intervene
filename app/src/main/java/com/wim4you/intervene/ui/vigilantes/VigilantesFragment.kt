package com.wim4you.intervene.ui.vigilantes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.wim4you.intervene.databinding.FragmentVigilantesBinding

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

override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}