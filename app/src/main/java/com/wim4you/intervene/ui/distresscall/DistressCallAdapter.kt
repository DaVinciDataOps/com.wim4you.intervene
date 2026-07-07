package com.wim4you.intervene.ui.distresscall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.AppModeController
import com.wim4you.intervene.R
import com.wim4you.intervene.data.DistressCallData

class DistressCallAdapter(
    private val onItemClick: (DistressCallData) -> Unit
) : ListAdapter<DistressCallData, DistressCallAdapter.ViewHolder>(DistressCallDiffCallback()) {

    private var selectedPosition = AppModeController.selectedDistressCall

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAlias: TextView = itemView.findViewById(R.id.tvAlias)
        val tvAddress: TextView = itemView.findViewById(R.id.tvAddress)
        val btnDistressCall: ImageButton = itemView.findViewById(R.id.ibDistress)
    }

    class DistressCallDiffCallback : DiffUtil.ItemCallback<DistressCallData>() {
        override fun areItemsTheSame(oldItem: DistressCallData, newItem: DistressCallData): Boolean =
            oldItem.id == newItem.id && oldItem.id != null

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
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_dark)
            )
            holder.btnDistressCall.setImageResource(R.mipmap.ic_vigilantes_patrolling)
            AppModeController.selectedDistressCall = position
        } else {
            holder.itemView.setBackgroundColor(android.R.attr.selectableItemBackground)
            holder.btnDistressCall.setImageResource(R.mipmap.ic_launcher_round)
        }

        holder.btnDistressCall.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                if (currentPosition == selectedPosition) {
                    selectedPosition = -1
                    AppModeController.selectedDistressCall = -1
                    notifyItemChanged(currentPosition)
                } else {
                    val oldPosition = selectedPosition
                    selectedPosition = currentPosition
                    AppModeController.selectedDistressCall = currentPosition
                    if (oldPosition != -1) {
                        notifyItemChanged(oldPosition)
                    }
                    notifyItemChanged(currentPosition)
                }
            }
        }
    }

    fun updateDistressCalls(newList: List<DistressCallData>) {
        submitList(newList)
    }
}
