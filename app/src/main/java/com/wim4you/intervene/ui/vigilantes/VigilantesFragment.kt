package com.wim4you.intervene.ui.vigilantes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.wim4you.intervene.databinding.FragmentVigilantesBinding

class VigilantesFragment : Fragment() {

private var _binding: FragmentVigilantesBinding? = null
  // This property is only valid between onCreateView and
  // onDestroyView.
  private val binding get() = _binding!!

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    val vigilantesViewModel =
            ViewModelProvider(this).get(VigilantesViewModel::class.java)

    _binding = FragmentVigilantesBinding.inflate(inflater, container, false)
    val root: View = binding.root

    val textView: TextView = binding.groupNameInputEditText
    vigilantesViewModel.text.observe(viewLifecycleOwner) {
      textView.text = it
    }
    return root
  }

override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}