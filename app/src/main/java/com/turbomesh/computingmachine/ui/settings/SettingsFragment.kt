package com.turbomesh.computingmachine.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import com.turbomesh.computingmachine.data.MeshSettings
import com.turbomesh.computingmachine.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    /** Prevents feedback loop when programmatically updating slider values. */
    private var isUpdatingUi = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // TTL slider
        binding.sliderTtl.addOnChangeListener { _, value, fromUser ->
            binding.textTtlValue.text = value.toInt().toString()
            if (fromUser && !isUpdatingUi) persistSettings()
        }

        // Heartbeat radio
        binding.radioHeartbeat.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUi) persistSettings()
        }

        // Max reconnect slider
        binding.sliderMaxReconnect.addOnChangeListener { _, value, fromUser ->
            binding.textMaxReconnectValue.text = value.toInt().toString()
            if (fromUser && !isUpdatingUi) persistSettings()
        }

        // Retries slider
        binding.sliderRetries.addOnChangeListener { _, value, fromUser ->
            binding.textRetriesValue.text = value.toInt().toString()
            if (fromUser && !isUpdatingUi) persistSettings()
        }

        // Reset button
        binding.buttonResetSettings.setOnClickListener {
            viewModel.resetToDefaults()
        }

        // Observe settings
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect { settings ->
                    applyToUi(settings)
                }
            }
        }
    }

    private fun applyToUi(settings: MeshSettings) {
        isUpdatingUi = true
        binding.sliderTtl.value = settings.defaultTtl.toFloat().coerceIn(
            binding.sliderTtl.valueFrom, binding.sliderTtl.valueTo
        )
        binding.textTtlValue.text = settings.defaultTtl.toString()

        val heartbeatId = when (settings.heartbeatIntervalMs) {
            5_000L -> binding.radioHeartbeat5s.id
            30_000L -> binding.radioHeartbeat30s.id
            else -> binding.radioHeartbeat10s.id
        }
        binding.radioHeartbeat.check(heartbeatId)

        binding.sliderMaxReconnect.value = settings.maxReconnectAttempts.toFloat().coerceIn(
            binding.sliderMaxReconnect.valueFrom, binding.sliderMaxReconnect.valueTo
        )
        binding.textMaxReconnectValue.text = settings.maxReconnectAttempts.toString()

        binding.sliderRetries.value = settings.messageRetries.toFloat().coerceIn(
            binding.sliderRetries.valueFrom, binding.sliderRetries.valueTo
        )
        binding.textRetriesValue.text = settings.messageRetries.toString()
        isUpdatingUi = false
    }

    private fun persistSettings() {
        val heartbeatMs = when (binding.radioHeartbeat.checkedRadioButtonId) {
            binding.radioHeartbeat5s.id -> 5_000L
            binding.radioHeartbeat30s.id -> 30_000L
            else -> 10_000L
        }
        viewModel.applySettings(
            MeshSettings(
                defaultTtl = binding.sliderTtl.value.toInt(),
                heartbeatIntervalMs = heartbeatMs,
                maxReconnectAttempts = binding.sliderMaxReconnect.value.toInt(),
                messageRetries = binding.sliderRetries.value.toInt()
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
