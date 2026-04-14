package com.turbomesh.computingmachine.ui.stats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.computingmachine.data.DeliveryStatsStore
import com.turbomesh.computingmachine.databinding.ItemStatsBinding

class StatsAdapter : ListAdapter<DeliveryStatsStore.NodeDeliveryStats, StatsAdapter.StatsViewHolder>(StatsDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatsViewHolder {
        val binding = ItemStatsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StatsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StatsViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StatsViewHolder(private val binding: ItemStatsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stats: DeliveryStatsStore.NodeDeliveryStats) {
            binding.textNodeId.text = stats.nodeId.take(8)
            binding.textSentCount.text = "Sent: ${stats.sent}"
            val ackRatePercent = (stats.ackRate * 100).toInt()
            binding.textAckRate.text = "Ack rate: $ackRatePercent%"
            binding.textFailedCount.text = "Failed: ${stats.failed}"
        }
    }

    class StatsDiffCallback : DiffUtil.ItemCallback<DeliveryStatsStore.NodeDeliveryStats>() {
        override fun areItemsTheSame(
            oldItem: DeliveryStatsStore.NodeDeliveryStats,
            newItem: DeliveryStatsStore.NodeDeliveryStats
        ) = oldItem.nodeId == newItem.nodeId

        override fun areContentsTheSame(
            oldItem: DeliveryStatsStore.NodeDeliveryStats,
            newItem: DeliveryStatsStore.NodeDeliveryStats
        ) = oldItem == newItem
    }
}
