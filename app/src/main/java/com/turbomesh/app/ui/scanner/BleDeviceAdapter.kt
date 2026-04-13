package com.turbomesh.app.ui.scanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.app.data.model.BleDevice
import com.turbomesh.app.databinding.ItemBleDeviceBinding

/**
 * RecyclerView adapter that displays discovered [BleDevice] rows.
 */
class BleDeviceAdapter : ListAdapter<BleDevice, BleDeviceAdapter.BleDeviceViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BleDeviceViewHolder {
        val binding = ItemBleDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BleDeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BleDeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class BleDeviceViewHolder(
        private val binding: ItemBleDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: BleDevice) {
            binding.tvDeviceName.text = device.displayName
            binding.tvDeviceAddress.text = device.address
            binding.tvDeviceRssi.text = device.signalStrengthLabel

            if (device.isMeshNode) {
                binding.tvMeshBadge.visibility = View.VISIBLE
                binding.tvMeshBadge.text = "MESH"
            } else {
                binding.tvMeshBadge.visibility = View.GONE
            }

            if (device.serviceUuids.isNotEmpty()) {
                binding.tvServiceUuids.text = device.serviceUuids.joinToString("\n")
                binding.tvServiceUuids.visibility = View.VISIBLE
            } else {
                binding.tvServiceUuids.visibility = View.GONE
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BleDevice>() {
            override fun areItemsTheSame(old: BleDevice, new: BleDevice) =
                old.address == new.address
            override fun areContentsTheSame(old: BleDevice, new: BleDevice) = old == new
        }
    }
}
