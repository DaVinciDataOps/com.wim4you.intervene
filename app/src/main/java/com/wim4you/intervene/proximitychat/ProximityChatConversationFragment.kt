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
import androidx.recyclerview.widget.RecyclerView
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.FragmentProximityChatConversationBinding
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
    private var stickToBottom = true
    private var lastMessageCount = 0

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

        messageAdapter = ChatMessageAdapter(
            onSpeakMessage = { message ->
                if (!message.isDeleted) {
                    speechHelper?.speak(message.text)
                }
            },
            onRemoveMessage = { message -> showRemoveMessageDialog(message) },
        )
        binding.recyclerMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.setHasFixedSize(true)
        binding.recyclerMessages.itemAnimator = null
        binding.recyclerMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                stickToBottom = !recyclerView.canScrollVertically(1)
            }
        })
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
        binding.btnDeleteChat.setOnClickListener { showRemoveChatDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        if (state.roomClosed) {
                            findNavController().navigateUp()
                            return@collectLatest
                        }

                        binding.tvChatHeaderTitle.text = state.roomTitle.ifBlank {
                            getString(R.string.menu_proximity_chat)
                        }
                        requireActivity().title = state.roomTitle.ifBlank {
                            getString(R.string.menu_proximity_chat)
                        }

                        binding.etMessage.isEnabled = state.canSendMessages
                        binding.btnSend.isEnabled = state.canSendMessages
                        binding.btnMic.isEnabled = state.canSendMessages

                        messageAdapter.submitList(state.messages) {
                            val messageCount = state.messages.size
                            val hasNewMessages = messageCount > lastMessageCount
                            lastMessageCount = messageCount
                            if (messageCount > 0 && (stickToBottom || (hasNewMessages && state.messages.last().isMine))) {
                                binding.recyclerMessages.scrollToPosition(messageCount - 1)
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

    private fun showRemoveMessageDialog(message: ChatMessageItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.chat_remove_message_title)
            .setMessage(R.string.chat_remove_message_body)
            .setPositiveButton(R.string.chat_remove_confirm) { _, _ ->
                viewModel.removeMessage(message.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            "remove_failed" -> R.string.chat_error_remove
            "remove_message_failed" -> R.string.chat_error_remove_message
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
