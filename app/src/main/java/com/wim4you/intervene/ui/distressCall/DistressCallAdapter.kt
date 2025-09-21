package com.wim4you.intervene.ui.distressCall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.AppState
import com.wim4you.intervene.R
import com.wim4you.intervene.data.DistressCallData

class DistressCallAdapter(
    private val onItemClick: (DistressCallData) -> Unit
) : ListAdapter<DistressCallData, DistressCallAdapter.ViewHolder>(DistressCallDiffCallback()) {

    private var selectedPosition = AppState.selectedDistressCall

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAlias: TextView = itemView.findViewById(R.id.tvAlias)
        val tvAddress: TextView = itemView.findViewById(R.id.tvAddress)
        val btnDistressCall: ImageButton = itemView.findViewById(R.id.ibDistress)
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

        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_dark))
            holder.btnDistressCall.setImageResource(R.mipmap.ic_vigilantes_patrolling) // Replace with your selected drawable
            AppState.selectedDistressCall = position
        } else {
            holder.itemView.setBackgroundColor(android.R.attr.selectableItemBackground)
            holder.btnDistressCall.setImageResource(R.mipmap.ic_launcher_round) // Replace with your normal drawable
        }

        holder.btnDistressCall.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                if (currentPosition == selectedPosition) {
                    // Deselect the currently selected item
                    val oldPosition = selectedPosition
                    selectedPosition = -1
                    AppState.selectedDistressCall = -1
                    notifyItemChanged(currentPosition)
                } else {
                    // Select a new item
                    val oldPosition = selectedPosition
                    selectedPosition = currentPosition
                    AppState.selectedDistressCall = currentPosition
                    if (oldPosition != -1) {
                        notifyItemChanged(oldPosition)
                    }
                    notifyItemChanged(currentPosition)
                }
            }
        }

//        holder.btnDistressCall.setOnClickListener {
//            val currentPosition = holder.bindingAdapterPosition
//            if (currentPosition != RecyclerView.NO_POSITION && currentPosition != selectedPosition) {
//                val oldPosition = selectedPosition
//                selectedPosition = currentPosition
//                if (oldPosition != -1) {
//                    notifyItemChanged(oldPosition)
//                }
//                notifyItemChanged(currentPosition)
//            }
//        }
    }

    fun updateDistressCalls(newList: List<DistressCallData>) {
        submitList(newList)
    }
}