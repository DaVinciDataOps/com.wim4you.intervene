package com.wim4you.intervene.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import com.wim4you.intervene.data.DistressCallData

class DistressCallAdapter(
    private val onItemClick: (DistressCallData) -> Unit
) : ListAdapter<DistressCallData, DistressCallAdapter.ViewHolder>(DistressCallDiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAlias: TextView = itemView.findViewById(R.id.tvAlias)
        val tvAddress: TextView = itemView.findViewById(R.id.tvAddress)
    }

    class DistressCallDiffCallback : DiffUtil.ItemCallback<DistressCallData>() {
        override fun areItemsTheSame(oldItem: DistressCallData, newItem: DistressCallData): Boolean =
            oldItem.alias == newItem.alias  // Assuming alias is unique ID

        override fun areContentsTheSame(oldItem: DistressCallData, newItem: DistressCallData): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_distress_call, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvAlias.text = item.alias
        holder.tvAddress.text = item.address
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    fun updateDistressCalls(newList: List<DistressCallData>) {
        submitList(newList)
    }
}