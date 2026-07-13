package com.wim4you.intervene.liverecording

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.databinding.ItemLiveRecordingBinding

class LiveRecordingAdapter(
    private val onRecordingClick: (LiveRecordingEntry) -> Unit,
    private val onRecordingLongClick: (LiveRecordingEntry) -> Unit,
    private val onRecordingDeleteClick: (LiveRecordingEntry) -> Unit,
) : ListAdapter<LiveRecordingEntry, LiveRecordingAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLiveRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemLiveRecordingBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: LiveRecordingEntry) {
            binding.recordingTitle.text = LiveRecordingLocalStore.formatTimestamp(entry.createdAtMillis)
            binding.recordingDuration.text = LiveRecordingLocalStore.formatDuration(entry.durationMillis)
            binding.root.setOnClickListener { onRecordingClick(entry) }
            binding.root.setOnLongClickListener {
                onRecordingLongClick(entry)
                true
            }
            binding.btnDeleteRecording.setOnClickListener { onRecordingDeleteClick(entry) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<LiveRecordingEntry>() {
        override fun areItemsTheSame(oldItem: LiveRecordingEntry, newItem: LiveRecordingEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LiveRecordingEntry, newItem: LiveRecordingEntry): Boolean {
            return oldItem == newItem
        }
    }
}
