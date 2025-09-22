package com.wim4you.intervene.ui.distresscall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentDistressListBinding

class DistressListFragment : Fragment() {
private val viewModel: DistressListViewModel by activityViewModels()
private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DistressCallAdapter
private var _binding: FragmentDistressListBinding? = null
  // This property is only valid between onCreateView and
  // onDestroyView.
  private val binding get() = _binding!!

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    val slideshowViewModel =
            ViewModelProvider(this).get(DistressListViewModel::class.java)

    _binding = FragmentDistressListBinding.inflate(inflater, container, false)
    val root: View = binding.root

//    val textView: TextView = binding.textSlideshow
//    slideshowViewModel.text.observe(viewLifecycleOwner) {
//      textView.text = it
//    }
    return root
  }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize RecyclerView and adapter FIRST
        recyclerView = view.findViewById(R.id.recyclerViewDistressCalls)
        adapter = DistressCallAdapter { distressCall ->
            // Handle click
            Toast.makeText(context, "Clicked: ${distressCall.alias} at ${distressCall.address}", Toast.LENGTH_SHORT).show()
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        // NOW observe LiveData - lambda will run safely after init
        viewModel.distressCalls.observe(viewLifecycleOwner) { distressCalls ->
            adapter.updateDistressCalls(distressCalls)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}