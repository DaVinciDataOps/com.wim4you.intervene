package com.wim4you.intervene.proximitychat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentProximityChatConversationBinding
import com.wim4you.intervene.ui.home.PatrolAlertSoundPlayer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProximityChatConversationFragment : Fragment() {

    private val viewModel: ProximityChatConversationViewModel by viewModels()
    private var _binding: FragmentProximityChatConversationBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageAdapter: ChatMessageAdapter
    private var speechHelper: SpeechInputHelper? = null
    private var hasPlayedIncomingChime = false

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            speechHelper?.startListening()
        } else {
            Toast.makeText(requireContext(), R.string.chat_mic_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProximityChatConversationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        messageAdapter = ChatMessageAdapter { message ->
            speechHelper?.speak(message.text)
        }
        binding.recyclerMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.adapter = messageAdapter

        speechHelper = SpeechInputHelper(
            context = requireContext(),
            onResult = { text -> viewModel.sendSpeechMessage(text) },
            onError = { message ->
                if (message != "Could not understand speech") {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            },
            onListeningChanged = { listening ->
                viewModel.setListening(listening)
            },
        )

        binding.etMessage.addTextChangedListener { editable ->
            viewModel.updateDraft(editable?.toString().orEmpty())
        }
        binding.btnSend.setOnClickListener { viewModel.sendTextMessage() }
        binding.btnMic.setOnClickListener { startSpeechInput() }
        binding.btnAcceptChat.setOnClickListener { viewModel.acceptInvite() }
        binding.btnDeclineChat.setOnClickListener {
            viewModel.declineInvite()
            findNavController().navigateUp()
        }
        binding.btnDeleteChat.setOnClickListener { showRemoveChatDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        val isRinging = state.roomStatus == ProximityChatConstants.ROOM_STATUS_RINGING
                        val showHeader = isRinging || state.isIncomingRing
                        binding.chatHeaderBar.isVisible = showHeader
                        binding.ivChatBell.isVisible = showHeader
                        binding.tvChatHeaderTitle.text = state.roomTitle.ifBlank {
                            getString(R.string.menu_proximity_chat)
                        }
                        requireActivity().title = state.roomTitle.ifBlank {
                            getString(R.string.menu_proximity_chat)
                        }

                        val isWaiting = isRinging && state.isInitiator
                        binding.tvWaitingForPickup.isVisible = isWaiting
                        binding.pickupBar.isVisible = state.isIncomingRing
                        binding.inputBar.isVisible = !state.isIncomingRing
                        binding.etMessage.isEnabled = state.canSendMessages
                        binding.btnSend.isEnabled = state.canSendMessages
                        binding.btnMic.isEnabled = state.canSendMessages

                        if (state.isIncomingRing && !hasPlayedIncomingChime) {
                            hasPlayedIncomingChime = true
                            PatrolAlertSoundPlayer.playChatNotification(requireContext())
                        }

                        messageAdapter.submitList(state.messages) {
                            if (state.messages.isNotEmpty()) {
                                binding.recyclerMessages.scrollToPosition(state.messages.lastIndex)
                            }
                        }
                        binding.progressSending.isVisible = state.isSending
                        binding.btnMic.isSelected = state.isListening
                        binding.tvListening.isVisible = state.isListening
                        if (binding.etMessage.text?.toString() != state.draftText) {
                            binding.etMessage.setText(state.draftText)
                            binding.etMessage.setSelection(state.draftText.length)
                        }
                        state.errorMessage?.let { showError(it) }
                    }
                }
                launch {
                    viewModel.pendingSpeechText.collectLatest { text ->
                        if (!text.isNullOrBlank()) {
                            speechHelper?.speak(text)
                            viewModel.consumePendingSpeech()
                        }
                    }
                }
            }
        }
    }

    private fun startSpeechInput() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> speechHelper?.startListening()
            else -> requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showRemoveChatDialog() {
        val title = viewModel.uiState.value.roomTitle.ifBlank {
            getString(R.string.menu_proximity_chat)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.chat_remove_title)
            .setMessage(getString(R.string.chat_remove_message, title))
            .setPositiveButton(R.string.chat_remove_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (viewModel.removeChat()) {
                        findNavController().navigateUp()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showError(key: String) {
        val messageRes = when (key) {
            "chat_load_failed" -> R.string.chat_error_load
            "send_failed" -> R.string.chat_error_send
            "accept_failed" -> R.string.chat_error_accept
            "decline_failed" -> R.string.chat_error_decline
            "remove_failed" -> R.string.chat_error_remove
            else -> R.string.chat_error_generic
        }
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        viewModel.clearError()
    }

    override fun onDestroyView() {
        speechHelper?.shutdown()
        speechHelper = null
        super.onDestroyView()
        _binding = null
    }
}
