package com.wim4you.intervene.proximitychat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import java.text.DateFormat
import java.util.Date

class ChatRoomAdapter(
    private val onRoomClick: (ChatRoomSummary) -> Unit,
) : ListAdapter<ChatRoomSummary, ChatRoomAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvChatRoomTitle)
        val subtitle: TextView = itemView.findViewById(R.id.tvChatRoomSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_room, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val room = getItem(position)
        val context = holder.itemView.context
        holder.title.text = room.displayName
        val typeLabel = if (room.isGroup) {
            context.getString(R.string.chat_group_label, room.participantCount)
        } else {
            context.getString(R.string.chat_direct_label)
        }
        val timeLabel = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(room.lastMessageAt))
        holder.subtitle.text = context.getString(R.string.chat_room_meta, typeLabel, timeLabel)
        holder.itemView.setOnClickListener { onRoomClick(room) }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ChatRoomSummary>() {
        override fun areItemsTheSame(oldItem: ChatRoomSummary, newItem: ChatRoomSummary): Boolean =
            oldItem.roomId == newItem.roomId

        override fun areContentsTheSame(oldItem: ChatRoomSummary, newItem: ChatRoomSummary): Boolean =
            oldItem == newItem
    }
}
