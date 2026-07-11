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
import com.wim4you.intervene.R
import com.wim4you.intervene.helpers.DistanceUtils
import com.wim4you.intervene.helpers.ElapsedTimeFormatter

class DistressCallAdapter(
    private val isIntervening: (String) -> Boolean,
    private val onItemClick: (DistressCallItem) -> Unit,
    private val onRespondClick: (DistressCallItem) -> Unit,
) : ListAdapter<DistressCallItem, DistressCallAdapter.ViewHolder>(DistressCallDiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvAlias: TextView = itemView.findViewById(R.id.tvAlias)
        val tvAddress: TextView = itemView.findViewById(R.id.tvAddress)
        val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        val btnDistressCall: ImageButton = itemView.findViewById(R.id.ibDistress)
    }

    class DistressCallDiffCallback : DiffUtil.ItemCallback<DistressCallItem>() {
        override fun areItemsTheSame(oldItem: DistressCallItem, newItem: DistressCallItem): Boolean =
            oldItem.call.id == newItem.call.id && oldItem.call.id != null

        override fun areContentsTheSame(oldItem: DistressCallItem, newItem: DistressCallItem): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_distress_call, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val call = item.call
        val distressId = call.id
        val intervening = distressId != null && isIntervening(distressId)
        val context = holder.itemView.context

        holder.tvAlias.text = call.alias
        holder.tvAddress.text = call.address ?: context.getString(R.string.distress_address_unknown)

        val distanceLabel = item.distanceMeters?.let { DistanceUtils.formatDistanceMeters(it) }
            ?: context.getString(R.string.distress_distance_unknown)
        val elapsedLabel = context.getString(
            R.string.distress_elapsed_ago,
            ElapsedTimeFormatter.formatElapsedSeconds(item.elapsedSeconds),
        )
        holder.tvMeta.text = context.getString(R.string.distress_meta_format, distanceLabel, elapsedLabel)

        holder.itemView.setOnClickListener { onItemClick(item) }

        when {
            intervening -> {
                holder.itemView.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.color_success),
                )
                holder.btnDistressCall.setImageResource(R.mipmap.ic_vigilantes_patrolling)
            }
            else -> {
                holder.itemView.setBackgroundResource(android.R.color.transparent)
                holder.btnDistressCall.setImageResource(R.mipmap.ic_launcher_round)
            }
        }

        holder.btnDistressCall.setOnClickListener { onRespondClick(item) }
    }

    fun updateItems(newList: List<DistressCallItem>) {
        submitList(newList)
    }
}
