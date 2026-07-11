package com.wim4you.intervene.proximitychat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.wim4you.intervene.R
import com.wim4you.intervene.helpers.DistanceUtils
import com.wim4you.intervene.profilepicture.ProfilePictureImageLoader
import kotlinx.coroutines.CoroutineScope

class NearbyChatUserAdapter(
    private val imageScope: CoroutineScope,
    private val onUserClick: (NearbyChatUser) -> Unit,
    private val onUserLongClick: (NearbyChatUser) -> Unit,
) : ListAdapter<NearbyChatUser, NearbyChatUserAdapter.ViewHolder>(DiffCallback()) {

    var selectionUiVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION_UI)
        }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: ShapeableImageView = itemView.findViewById(R.id.ivNearbyAvatar)
        val alias: TextView = itemView.findViewById(R.id.tvNearbyAlias)
        val distance: TextView = itemView.findViewById(R.id.tvNearbyDistance)
        val checkbox: CheckBox = itemView.findViewById(R.id.cbNearbySelect)
        val bell: ImageView = itemView.findViewById(R.id.ivNearbyBell)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nearby_chat_user, parent, false)
        val holder = ViewHolder(view)
        holder.itemView.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            val user = getItem(position)
            if (selectionUiVisible) {
                onUserLongClick(user)
            } else {
                onUserClick(user)
            }
        }
        holder.itemView.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            onUserLongClick(getItem(position))
            true
        }
        holder.checkbox.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            onUserLongClick(getItem(position))
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bindUser(holder, getItem(position), fullBind = true)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION_UI)) {
            bindSelectionUi(holder, getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindUser(holder: ViewHolder, user: NearbyChatUser, fullBind: Boolean) {
        if (fullBind) {
            val context = holder.itemView.context
            holder.alias.text = user.alias
            holder.distance.text = user.distanceMeters?.let { DistanceUtils.formatDistanceMeters(it) }
                ?: context.getString(R.string.chat_distance_unknown)
            holder.bell.isVisible = user.hasUnreadIndicator
            holder.bell.contentDescription = context.getString(R.string.chat_unread_label)
            ProfilePictureImageLoader.bind(holder.avatar, user.profilePictureUrl, imageScope)
        }
        bindSelectionUi(holder, user)
    }

    private fun bindSelectionUi(holder: ViewHolder, user: NearbyChatUser) {
        holder.checkbox.isChecked = user.isSelected
        holder.checkbox.visibility = if (selectionUiVisible) View.VISIBLE else View.GONE
    }

    private class DiffCallback : DiffUtil.ItemCallback<NearbyChatUser>() {
        override fun areItemsTheSame(oldItem: NearbyChatUser, newItem: NearbyChatUser): Boolean =
            oldItem.uid == newItem.uid

        override fun areContentsTheSame(oldItem: NearbyChatUser, newItem: NearbyChatUser): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val PAYLOAD_SELECTION_UI = "selection_ui"
    }
}
