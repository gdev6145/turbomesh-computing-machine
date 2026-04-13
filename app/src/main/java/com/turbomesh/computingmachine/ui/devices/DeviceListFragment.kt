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
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
                else {
                    viewModel.provisionNode(node)
                    // Feature 12: show pairing PIN dialog after provisioning
                    showPairingPinDialog(node.id)
                }
            },
            onRenameClick = { node -> showRenameDialog(node.id, node.displayName) }
        )

        binding.recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDevices.adapter = adapter

        // Long-press on device for mute/signal-history context menu
        binding.recyclerDevices.addOnItemTouchListener(
            object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                private val gestureDetector = android.view.GestureDetector(
                    requireContext(),
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: android.view.MotionEvent) {
                            val child = binding.recyclerDevices.findChildViewUnder(e.x, e.y) ?: return
                            val pos = binding.recyclerDevices.getChildAdapterPosition(child)
                            if (pos < 0) return
                            val node = adapter.currentList.getOrNull(pos) ?: return
                            showDeviceContextMenu(node)
                        }
                    }
                )
                override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent): Boolean {
                    gestureDetector.onTouchEvent(e)
                    return false
                }
            }
        )

        binding.fabScan.setOnClickListener { viewModel.toggleScan() }
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.toggleScan()
            binding.swipeRefresh.isRefreshing = false
        }

        // Feature 18: Status button in toolbar to open bottom-sheet
        binding.toolbarDevices.inflateMenu(R.menu.menu_devices)
        binding.toolbarDevices.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_set_status -> { showStatusBottomSheet(); true }
                R.id.action_qr_provision -> { showQrProvisionDialog(); true }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.discoveredNodes.collect { nodes ->
                        adapter.submitList(nodes)
                        binding.textEmptyState.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.fabScan.setImageResource(if (scanning) R.drawable.ic_stop else R.drawable.ic_scan)
                        binding.scanningIndicator.visibility = if (scanning) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.muteStates.collect { states ->
                        adapter.muteStates = states
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
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.renameNode(nodeId, editText.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Feature 12: Pairing PIN dialog
    // -------------------------------------------------------------------------

    private fun showPairingPinDialog(nodeId: String) {
        val pin = viewModel.derivePairingPin(nodeId)
        if (pin == null) {
            // No public key yet — exchange keys first
            viewModel.sendPublicKey(nodeId)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pairing_pin_title))
            .setMessage(getString(R.string.pairing_pin_message, pin))
            .setPositiveButton(getString(R.string.pin_matches)) { _, _ ->
                viewModel.markNodeVerified(nodeId)
            }
            .setNegativeButton(getString(R.string.pin_mismatch)) { _, _ ->
                viewModel.unprovisionNode(viewModel.discoveredNodes.value.firstOrNull { it.id == nodeId }
                    ?: return@setNegativeButton)
            }
            .setCancelable(false)
            .show()
    }

    // -------------------------------------------------------------------------
    // Feature 18: Status bottom-sheet
    // -------------------------------------------------------------------------

    private fun showStatusBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_status, null)
        val inputLayout = sheetView.findViewById<TextInputLayout>(R.id.layout_status_input)
        val editText = sheetView.findViewById<TextInputEditText>(R.id.edit_status_input)
        editText.setText(viewModel.getMyStatus())
        sheetView.findViewById<View>(R.id.button_status_save).setOnClickListener {
            val status = editText.text?.toString()?.trim() ?: ""
            viewModel.setMyStatus(status)
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    // -------------------------------------------------------------------------
    // Feature 26: Device context menu (mute/signal history)
    // -------------------------------------------------------------------------

    private fun showDeviceContextMenu(node: com.turbomesh.computingmachine.mesh.MeshNode) {
        val options = arrayOf(
            getString(R.string.mute_1h),
            getString(R.string.mute_8h),
            getString(R.string.unmute),
            getString(R.string.signal_history)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(node.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.muteNode(node.id, 1)
                    1 -> viewModel.muteNode(node.id, 8)
                    2 -> viewModel.muteNode(node.id, 0)
                    3 -> navigateToSignalHistory(node.id)
                }
            }
            .show()
    }

    private fun navigateToSignalHistory(nodeId: String) {
        val navController = NavHostFragment.findNavController(this)
        val bundle = android.os.Bundle().apply { putString("nodeId", nodeId) }
        navController.navigate(R.id.rssiHistoryFragment, bundle)
    }

    private fun showQrProvisionDialog() {
        val nodes = viewModel.discoveredNodes.value
        if (nodes.isEmpty()) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root, getString(R.string.no_devices_found), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
            return
        }
        val nodeNames = nodes.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qr_provision))
            .setItems(nodeNames) { _, idx ->
                val node = nodes[idx]
                val bitmap = com.turbomesh.computingmachine.mesh.QrCodeHelper
                    .generateQrBitmap(node.id, node.id)
                val imageView = android.widget.ImageView(requireContext()).apply {
                    setImageBitmap(bitmap)
                    android.widget.LinearLayout.LayoutParams(400, 400).also { layoutParams = it }
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.qr_provision_scan_title, node.displayName))
                    .setView(imageView)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .show()
    }

}
