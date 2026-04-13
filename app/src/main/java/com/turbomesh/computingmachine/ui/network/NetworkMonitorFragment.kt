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
import com.turbomesh.computingmachine.data.models.NodeStats
import com.turbomesh.computingmachine.databinding.FragmentNetworkMonitorBinding
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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
                    viewModel.healthHistory.collect { history ->
                        binding.healthSparkline.setReadings(history)
                    }
                }
                launch {
                    viewModel.activeNodes.collect { nodes ->
                        rebuildActiveNodes(nodes)
                    }
                }
                launch {
                    // Refresh active-node uptime display every 30 s
                    while (true) {
                        kotlinx.coroutines.delay(30_000)
                        rebuildActiveNodes(viewModel.activeNodes.value)
                    }
                }
                launch {
                    viewModel.knownNodes.collect { nodeIds ->
                        rebuildRoutingTable(nodeIds, viewModel.perNodeStats.value)
                    }
                }
                launch {
                    viewModel.perNodeStats.collect { stats ->
                        rebuildRoutingTable(viewModel.knownNodes.value, stats)
                    }
                }
            }
        }
    }

    private fun rebuildActiveNodes(nodes: List<MeshNode>) {
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
                nodeView.findViewById<TextView>(R.id.text_node_name).text = node.displayName
                nodeView.findViewById<TextView>(R.id.text_node_address).text = node.address
                nodeView.findViewById<TextView>(R.id.text_node_connection_status).text =
                    if (node.isConnected) getString(R.string.status_connected)
                    else getString(R.string.status_provisioned)
                val uptimeView = nodeView.findViewById<TextView>(R.id.text_uptime)
                if (uptimeView != null) {
                    if (node.isConnected && node.connectedSinceMs > 0L) {
                        val elapsedMs = System.currentTimeMillis() - node.connectedSinceMs
                        uptimeView.text = getString(R.string.uptime_format, formatUptime(elapsedMs))
                        uptimeView.visibility = View.VISIBLE
                    } else {
                        uptimeView.visibility = View.GONE
                    }
                }
                binding.containerActiveNodes.addView(nodeView)
            }
        }
    }

    private fun rebuildRoutingTable(nodeIds: Set<String>, stats: Map<String, NodeStats>) {
        binding.containerRoutingTable.removeAllViews()
        if (nodeIds.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = getString(R.string.no_known_nodes)
                setPadding(16, 16, 16, 16)
            }
            binding.containerRoutingTable.addView(emptyText)
        } else {
            nodeIds.forEach { nodeId ->
                val nodeStats = stats[nodeId]
                val label = if (nodeStats != null) {
                    getString(R.string.routing_entry_format, nodeId, nodeStats.sent, nodeStats.received)
                } else {
                    nodeId
                }
                val entryView = TextView(requireContext()).apply {
                    text = label
                    textSize = 12f
                    setPadding(16, 8, 16, 8)
                    setTextColor(requireContext().getColor(R.color.status_provisioned))
                }
                binding.containerRoutingTable.addView(entryView)
            }
        }
    }

    private fun formatUptime(elapsedMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%dh %02dm %02ds".format(hours, minutes, seconds)
        } else {
            "%dm %02ds".format(minutes, seconds)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
