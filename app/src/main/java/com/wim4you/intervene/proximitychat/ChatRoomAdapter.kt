package com.wim4you.intervene.proximitychat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import java.text.DateFormat
import java.util.Date

class ChatRoomAdapter(
    private val onRoomClick: (ChatRoomSummary) -> Unit,
    private val onRoomLongClick: (ChatRoomSummary) -> Unit,
    private val onRoomDeleteClick: (ChatRoomSummary) -> Unit,
) : ListAdapter<ChatRoomSummary, ChatRoomAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvChatRoomTitle)
        val subtitle: TextView = itemView.findViewById(R.id.tvChatRoomSubtitle)
        val bell: ImageView = itemView.findViewById(R.id.ivChatRoomBell)
        val deleteButton: ImageButton = itemView.findViewById(R.id.btnDeleteChatRoom)
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
        val showBell = room.hasUnreadForMe || room.hasUnreadByOthers
        holder.bell.isVisible = showBell
        holder.bell.contentDescription = context.getString(R.string.chat_unread_label)
        val typeLabel = if (room.isGroup) {
            context.getString(R.string.chat_group_label, room.participantCount)
        } else {
            context.getString(R.string.chat_direct_label)
        }
        val timeLabel = TIME_FORMATTER.format(Date(room.lastMessageAt))
        holder.subtitle.text = context.getString(R.string.chat_room_meta, typeLabel, timeLabel)
        holder.itemView.setOnClickListener { onRoomClick(room) }
        holder.itemView.setOnLongClickListener {
            onRoomLongClick(room)
            true
        }
        holder.deleteButton.setOnClickListener { onRoomDeleteClick(room) }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ChatRoomSummary>() {
        override fun areItemsTheSame(oldItem: ChatRoomSummary, newItem: ChatRoomSummary): Boolean =
            oldItem.roomId == newItem.roomId

        override fun areContentsTheSame(oldItem: ChatRoomSummary, newItem: ChatRoomSummary): Boolean =
            oldItem == newItem
    }

    companion object {
        private val TIME_FORMATTER: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    }
}
