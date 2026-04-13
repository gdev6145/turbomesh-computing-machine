package com.turbomesh.computingmachine.ui.network

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.turbomesh.computingmachine.R
import com.turbomesh.computingmachine.databinding.FragmentNetworkMonitorBinding
import kotlinx.coroutines.launch

class NetworkMonitorFragment : Fragment() {

    private var _binding: FragmentNetworkMonitorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NetworkViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworkMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.networkStats.collect { stats ->
                        binding.textTotalNodes.text = stats.totalNodes.toString()
                        binding.textConnectedNodes.text = stats.connectedNodes.toString()
                        binding.textMessagesSent.text = stats.messagesSent.toString()
                        binding.textMessagesReceived.text = stats.messagesReceived.toString()

                        val healthPercent = (stats.networkHealth * 100).toInt()
                        binding.progressNetworkHealth.progress = healthPercent
                        binding.textNetworkHealthPercent.text =
                            getString(R.string.health_percent_format, healthPercent)

                        val healthColor = when {
                            stats.networkHealth >= 0.75f -> requireContext().getColor(R.color.health_good)
                            stats.networkHealth >= 0.4f -> requireContext().getColor(R.color.health_medium)
                            else -> requireContext().getColor(R.color.health_poor)
                        }
                        binding.progressNetworkHealth.setIndicatorColor(healthColor)
                    }
                }
                launch {
                    viewModel.activeNodes.collect { nodes ->
                        binding.containerActiveNodes.removeAllViews()
                        if (nodes.isEmpty()) {
                            val emptyText = TextView(requireContext()).apply {
                                text = getString(R.string.no_active_nodes)
                                setPadding(16, 16, 16, 16)
                            }
                            binding.containerActiveNodes.addView(emptyText)
                        } else {
                            nodes.forEach { node ->
                                val nodeView = LayoutInflater.from(requireContext())
                                    .inflate(R.layout.item_active_node, binding.containerActiveNodes, false)
                                nodeView.findViewById<TextView>(R.id.text_node_name).text =
                                    node.displayName
                                nodeView.findViewById<TextView>(R.id.text_node_address).text = node.address
                                nodeView.findViewById<TextView>(R.id.text_node_connection_status).text =
                                    if (node.isConnected) getString(R.string.status_connected)
                                    else getString(R.string.status_provisioned)
                                binding.containerActiveNodes.addView(nodeView)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
