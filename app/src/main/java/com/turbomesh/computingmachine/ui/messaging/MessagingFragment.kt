package com.turbomesh.computingmachine.ui.messaging

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.turbomesh.computingmachine.R
import com.turbomesh.computingmachine.databinding.FragmentMessagingBinding
import com.turbomesh.computingmachine.mesh.MeshMessage
import kotlinx.coroutines.launch

class MessagingFragment : Fragment() {

    private var _binding: FragmentMessagingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MessagingViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var destinationArrayAdapter: ArrayAdapter<String>
    private val destinations = mutableListOf(MeshMessage.BROADCAST_DESTINATION)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        messageAdapter = MessageAdapter()
        binding.recyclerMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.adapter = messageAdapter

        // Swipe-left to delete a message
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val msg = messageAdapter.currentList.getOrNull(viewHolder.adapterPosition)
                    ?: return
                viewModel.deleteMessage(msg.id)
            }
        }).attachToRecyclerView(binding.recyclerMessages)

        destinationArrayAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            destinations
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerDestination.adapter = destinationArrayAdapter

        // Toolbar overflow menu — clear chat
        binding.toolbarMessaging.inflateMenu(R.menu.menu_messaging)
        binding.toolbarMessaging.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_messages -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.clear_messages)
                        .setMessage(R.string.clear_messages_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.clearMessages() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                else -> false
            }
        }

        // Search bar
        binding.editSearch.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }

        binding.buttonSend.setOnClickListener {
            val text = binding.editMessageInput.text?.toString() ?: return@setOnClickListener
            viewModel.sendMessage(text)
            binding.editMessageInput.text?.clear()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredMessages.collect { messages ->
                        messageAdapter.submitList(messages)
                        if (messages.isNotEmpty()) {
                            binding.recyclerMessages.scrollToPosition(messages.size - 1)
                        }
                    }
                }
                launch {
                    viewModel.availableDestinations.collect { dests ->
                        destinations.clear()
                        destinations.addAll(dests)
                        destinationArrayAdapter.notifyDataSetChanged()
                    }
                }
                launch {
                    viewModel.deliveryFailures.collect { failedId ->
                        Snackbar.make(
                            binding.root,
                            getString(R.string.delivery_failed_format, failedId.take(8)),
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        binding.spinnerDestination.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    viewModel.selectDestination(destinations.getOrNull(position) ?: MeshMessage.BROADCAST_DESTINATION)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

