package com.wim4you.intervene.proximitychat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import com.wim4you.intervene.helpers.DistanceUtils

class NearbyChatUserAdapter(
    private val selectedIds: () -> Set<String>,
    private val onUserClick: (NearbyChatUser) -> Unit,
    private val onUserLongClick: (NearbyChatUser) -> Unit,
) : ListAdapter<NearbyChatUser, NearbyChatUserAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val alias: TextView = itemView.findViewById(R.id.tvNearbyAlias)
        val distance: TextView = itemView.findViewById(R.id.tvNearbyDistance)
        val checkbox: CheckBox = itemView.findViewById(R.id.cbNearbySelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nearby_chat_user, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = getItem(position)
        val context = holder.itemView.context
        holder.alias.text = user.alias
        holder.distance.text = user.distanceMeters?.let { DistanceUtils.formatDistanceMeters(it) }
            ?: context.getString(R.string.chat_distance_unknown)
        val isSelected = user.uid in selectedIds()
        holder.checkbox.isChecked = isSelected
        holder.checkbox.visibility = if (selectedIds().isNotEmpty()) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            if (selectedIds().isNotEmpty()) {
                onUserLongClick(user)
            } else {
                onUserClick(user)
            }
        }
        holder.itemView.setOnLongClickListener {
            onUserLongClick(user)
            true
        }
        holder.checkbox.setOnClickListener { onUserLongClick(user) }
    }

    private class DiffCallback : DiffUtil.ItemCallback<NearbyChatUser>() {
        override fun areItemsTheSame(oldItem: NearbyChatUser, newItem: NearbyChatUser): Boolean =
            oldItem.uid == newItem.uid

        override fun areContentsTheSame(oldItem: NearbyChatUser, newItem: NearbyChatUser): Boolean =
            oldItem == newItem
    }
}
