package com.wim4you.intervene.recording

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.ItemRecordingBinding

class RecordingAdapter(
    private val onItemClick: (RecordingListItem) -> Unit,
    private val onItemDeleteClick: (RecordingListItem) -> Unit,
) : ListAdapter<RecordingListItem, RecordingAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(
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
        private val binding: ItemRecordingBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecordingListItem) {
            when (item) {
                is RecordingListItem.SingleRecording -> {
                    binding.recordingTitle.text = item.username
                    binding.recordingMeta.text = binding.root.context.getString(
                        R.string.recording_meta_single,
                        RecordingLocalStore.formatTimestamp(item.createdAtMillis),
                        RecordingLocalStore.formatDuration(item.durationMillis),
                    )
                }
                is RecordingListItem.DistressSession -> {
                    binding.recordingTitle.text = item.username
                    binding.recordingMeta.text = binding.root.context.getString(
                        R.string.recording_meta_distress,
                        RecordingLocalStore.formatTimestamp(item.startedAtMillis),
                        item.segmentCount,
                    )
                }
            }
            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDeleteRecording.setOnClickListener { onItemDeleteClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RecordingListItem>() {
        override fun areItemsTheSame(oldItem: RecordingListItem, newItem: RecordingListItem): Boolean {
            return when {
                oldItem is RecordingListItem.SingleRecording && newItem is RecordingListItem.SingleRecording ->
                    oldItem.relativePath == newItem.relativePath
                oldItem is RecordingListItem.DistressSession && newItem is RecordingListItem.DistressSession ->
                    oldItem.sessionPath == newItem.sessionPath
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: RecordingListItem, newItem: RecordingListItem): Boolean {
            return oldItem == newItem
        }
    }
}
