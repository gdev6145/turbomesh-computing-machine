package com.turbomesh.computingmachine.ui.history

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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.computingmachine.data.db.RssiLogEntity
import com.turbomesh.computingmachine.databinding.FragmentRssiHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RssiHistoryFragment : Fragment() {

    private var _binding: FragmentRssiHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RssiHistoryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRssiHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val nodeId = arguments?.getString("nodeId") ?: ""
        viewModel.init(nodeId)

        val adapter = RssiHistoryListAdapter()
        binding.recyclerRssiHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRssiHistory.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rssiHistory.collect { entries ->
                        val readings = entries.map { it.rssi.toFloat() }
                        binding.rssiSparklineHistory.setReadings(readings)
                        adapter.submitList(entries)
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

class RssiHistoryListAdapter :
    ListAdapter<RssiLogEntity, RssiHistoryListAdapter.RssiViewHolder>(RssiDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RssiViewHolder {
        val tv = TextView(parent.context).apply {
            setPadding(32, 8, 32, 8)
        }
        return RssiViewHolder(tv)
    }

    override fun onBindViewHolder(holder: RssiViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RssiViewHolder(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(entry: RssiLogEntity) {
            tv.text = "${timeFormat.format(Date(entry.timestampMs))}  ${entry.rssi} dBm"
        }
    }

    class RssiDiffCallback : DiffUtil.ItemCallback<RssiLogEntity>() {
        override fun areItemsTheSame(oldItem: RssiLogEntity, newItem: RssiLogEntity) =
            oldItem.rowId == newItem.rowId
        override fun areContentsTheSame(oldItem: RssiLogEntity, newItem: RssiLogEntity) =
            oldItem == newItem
    }
}
