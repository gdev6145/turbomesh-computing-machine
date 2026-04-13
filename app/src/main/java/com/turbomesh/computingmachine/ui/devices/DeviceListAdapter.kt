package com.turbomesh.computingmachine.ui.devices

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.computingmachine.R
import com.turbomesh.computingmachine.databinding.ItemDeviceBinding
import com.turbomesh.computingmachine.mesh.MeshNode

class DeviceListAdapter(
    private val onConnectClick: (MeshNode) -> Unit,
    private val onProvisionClick: (MeshNode) -> Unit,
    private val onRenameClick: (MeshNode) -> Unit
) : ListAdapter<MeshNode, DeviceListAdapter.DeviceViewHolder>(NodeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(node: MeshNode) {
            binding.textDeviceName.text = node.displayName
            binding.textDeviceAddress.text = node.address
            binding.textDeviceRssi.text = itemView.context.getString(R.string.rssi_format, node.rssi)

            val connectionStatus = when {
                node.isConnected -> itemView.context.getString(R.string.status_connected)
                node.isProvisioned -> itemView.context.getString(R.string.status_provisioned)
                else -> itemView.context.getString(R.string.status_discovered)
            }
            binding.textDeviceStatus.text = connectionStatus

            binding.buttonConnect.text = if (node.isConnected)
                itemView.context.getString(R.string.disconnect)
            else
                itemView.context.getString(R.string.connect)

            binding.buttonProvision.text = if (node.isProvisioned)
                itemView.context.getString(R.string.unprovision)
            else
                itemView.context.getString(R.string.provision)

            binding.buttonConnect.setOnClickListener { onConnectClick(node) }
            binding.buttonProvision.setOnClickListener { onProvisionClick(node) }
            binding.buttonRename.setOnClickListener { onRenameClick(node) }

            val rssiColor = when {
                node.rssi >= -60 -> R.color.rssi_good
                node.rssi >= -80 -> R.color.rssi_medium
                else -> R.color.rssi_poor
            }
            binding.textDeviceRssi.setTextColor(
                itemView.context.getColor(rssiColor)
            )
            binding.textRssiTrend.text = node.rssiTrend
            binding.textRssiTrend.setTextColor(
                itemView.context.getColor(rssiColor)
            )
        }
    }

    class NodeDiffCallback : DiffUtil.ItemCallback<MeshNode>() {
        override fun areItemsTheSame(oldItem: MeshNode, newItem: MeshNode) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MeshNode, newItem: MeshNode) = oldItem == newItem
    }
}
