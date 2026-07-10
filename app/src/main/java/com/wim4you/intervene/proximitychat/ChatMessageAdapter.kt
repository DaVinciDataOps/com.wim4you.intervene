package com.wim4you.intervene.proximitychat

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
import java.text.DateFormat
import java.util.Date

class ChatMessageAdapter(
    private val onSpeakMessage: (ChatMessageItem) -> Unit,
    private val onRemoveMessage: (ChatMessageItem) -> Unit,
) : ListAdapter<ChatMessageItem, ChatMessageAdapter.ViewHolder>(DiffCallback()) {

    init {
        setHasStableIds(true)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val sender: TextView = itemView.findViewById(R.id.tvMessageSender)
        val body: TextView = itemView.findViewById(R.id.tvMessageBody)
        val time: TextView = itemView.findViewById(R.id.tvMessageTime)
        val speechBadge: TextView = itemView.findViewById(R.id.tvSpeechBadge)
        val speakButton: ImageButton = itemView.findViewById(R.id.btnSpeakMessage)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isMine) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == VIEW_TYPE_SENT) {
            R.layout.item_chat_message_sent
        } else {
            R.layout.item_chat_message_received
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        val holder = ViewHolder(view)
        holder.speakButton.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            val message = getItem(position)
            if (!message.isDeleted) {
                onSpeakMessage(message)
            }
        }
        holder.itemView.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            val message = getItem(position)
            if (message.isMine && !message.isDeleted) {
                onRemoveMessage(message)
                true
            } else {
                false
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = getItem(position)
        val context = holder.itemView.context
        holder.sender.text = if (message.isMine) {
            context.getString(R.string.chat_you)
        } else {
            message.senderAlias
        }
        if (message.isDeleted) {
            holder.body.text = context.getString(R.string.chat_message_removed)
            holder.body.setTextColor(ContextCompat.getColor(context, R.color.color_distress))
            holder.speechBadge.visibility = View.GONE
        } else {
            holder.body.text = message.text
            if (getItemViewType(position) == VIEW_TYPE_SENT) {
                holder.body.setTextColor(ContextCompat.getColor(context, android.R.color.white))
            } else {
                holder.body.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            holder.speechBadge.visibility = if (message.isSpeech) View.VISIBLE else View.GONE
        }
        holder.time.text = TIME_FORMATTER.format(Date(message.timestamp))
        holder.speakButton.isEnabled = !message.isDeleted
        holder.speakButton.alpha = if (message.isDeleted) 0.4f else 1f
    }

    private class DiffCallback : DiffUtil.ItemCallback<ChatMessageItem>() {
        override fun areItemsTheSame(oldItem: ChatMessageItem, newItem: ChatMessageItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessageItem, newItem: ChatMessageItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private val TIME_FORMATTER: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    }
}
