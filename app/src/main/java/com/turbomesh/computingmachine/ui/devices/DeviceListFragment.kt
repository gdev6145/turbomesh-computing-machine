package com.turbomesh.computingmachine.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.turbomesh.computingmachine.R
import com.turbomesh.computingmachine.databinding.FragmentDeviceListBinding
import kotlinx.coroutines.launch

class DeviceListFragment : Fragment() {

    private var _binding: FragmentDeviceListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceViewModel by viewModels()
    private lateinit var adapter: DeviceListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DeviceListAdapter(
            onConnectClick = { node ->
                if (node.isConnected) viewModel.disconnectNode(node)
                else viewModel.connectNode(node)
            },
            onProvisionClick = { node ->
                if (node.isProvisioned) viewModel.unprovisionNode(node)
                else viewModel.provisionNode(node)
            },
            onRenameClick = { node -> showRenameDialog(node.id, node.displayName) }
        )

        binding.recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDevices.adapter = adapter

        binding.fabScan.setOnClickListener {
            viewModel.toggleScan()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.toggleScan()
            binding.swipeRefresh.isRefreshing = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.discoveredNodes.collect { nodes ->
                        adapter.submitList(nodes)
                        binding.textEmptyState.visibility =
                            if (nodes.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.fabScan.setImageResource(
                            if (scanning) R.drawable.ic_stop else R.drawable.ic_scan
                        )
                        binding.scanningIndicator.visibility =
                            if (scanning) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showRenameDialog(nodeId: String, currentName: String) {
        val editText = EditText(requireContext()).apply {
            setText(currentName)
            hint = getString(R.string.rename_node_hint)
            selectAll()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_node_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.renameNode(nodeId, editText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
