package com.wim4you.intervene.distressstream

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.databinding.ItemDistressRecordingBinding

class DistressRecordingAdapter(
    private val onSessionClick: (DistressRecordingSession) -> Unit,
    private val onSessionDeleteClick: (DistressRecordingSession) -> Unit,
) : ListAdapter<DistressRecordingSession, DistressRecordingAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDistressRecordingBinding.inflate(
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
        private val binding: ItemDistressRecordingBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(session: DistressRecordingSession) {
            val title = session.distressAlias?.ifBlank { null }
                ?: session.distressId.take(8)
            binding.recordingTitle.text = title
            binding.recordingMeta.text = binding.root.context.getString(
                com.wim4you.intervene.R.string.distress_recording_meta,
                DistressRecordingLocalStore.formatTimestamp(session.startedAtMillis),
                session.segmentCount,
            )
            binding.root.setOnClickListener { onSessionClick(session) }
            binding.btnDeleteRecording.setOnClickListener { onSessionDeleteClick(session) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DistressRecordingSession>() {
        override fun areItemsTheSame(
            oldItem: DistressRecordingSession,
            newItem: DistressRecordingSession,
        ): Boolean = oldItem.sessionId == newItem.sessionId

        override fun areContentsTheSame(
            oldItem: DistressRecordingSession,
            newItem: DistressRecordingSession,
        ): Boolean = oldItem == newItem
    }
}
