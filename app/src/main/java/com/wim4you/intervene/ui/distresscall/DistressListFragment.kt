package com.wim4you.intervene.ui.distresscall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentDistressListBinding
import com.wim4you.intervene.ui.map.MapDataViewModel

class DistressListFragment : Fragment() {

    private val mapDataViewModel: MapDataViewModel by activityViewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DistressCallAdapter
    private var _binding: FragmentDistressListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDistressListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerViewDistressCalls)
        adapter = DistressCallAdapter { distressCall ->
            Toast.makeText(
                context,
                "Clicked: ${distressCall.alias} at ${distressCall.address}",
                Toast.LENGTH_SHORT
            ).show()
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        mapDataViewModel.distressCalls.observe(viewLifecycleOwner) { distressCalls ->
            adapter.updateDistressCalls(distressCalls)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
