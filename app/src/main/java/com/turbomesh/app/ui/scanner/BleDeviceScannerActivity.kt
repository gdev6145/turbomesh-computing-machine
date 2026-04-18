package com.turbomesh.app.ui.scanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.turbomesh.app.R
import com.turbomesh.app.data.model.BleDevice
import com.turbomesh.app.databinding.ActivityBleScannerBinding
import java.util.Locale

/**
 * BleDeviceScannerActivity
 *
 * Performs a live Bluetooth LE scan and displays discovered devices in a RecyclerView.
 * Devices that advertise a Bluetooth Mesh Provisioning or Proxy service UUID are
 * highlighted as mesh nodes.
 *
 * Handles runtime permissions for both Android 12+ (BLUETOOTH_SCAN / BLUETOOTH_CONNECT)
 * and earlier versions (ACCESS_FINE_LOCATION).
 */
class BleDeviceScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleScannerBinding
    private val adapter = BleDeviceAdapter()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanning = false

    /** In-memory map of address → BleDevice to deduplicate and update RSSI in place. */
    private val deviceMap = mutableMapOf<String, BleDevice>()

    // ---------------------------------------------------------------------------
    // Permission launcher
    // ---------------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            startBleScan()
        } else {
            showPermissionDeniedMessage()
        }
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBleScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.ble_scanner_title)
            setDisplayHomeAsUpEnabled(true)
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter

        setupRecyclerView()
        setupButtons()
    }

    override fun onStop() {
        super.onStop()
        stopBleScan()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ---------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------

    private fun setupRecyclerView() {
        binding.recyclerViewDevices.apply {
            layoutManager = LinearLayoutManager(this@BleDeviceScannerActivity)
            adapter = this@BleDeviceScannerActivity.adapter
            setHasFixedSize(false)
        }
    }

    private fun setupButtons() {
        binding.btnStartScan.setOnClickListener {
            if (scanning) {
                stopBleScan()
            } else {
                checkPermissionsAndScan()
            }
        }
        binding.btnClearDevices.setOnClickListener {
            deviceMap.clear()
            adapter.submitList(emptyList())
            binding.tvEmpty.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------------------
    // BLE scan
    // ---------------------------------------------------------------------------

    private fun checkPermissionsAndScan() {
        val btAdapter = bluetoothAdapter
        if (btAdapter == null) {
            Snackbar.make(
                binding.root,
                R.string.ble_not_supported,
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        if (!btAdapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        val missing = requiredPermissions().filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBleScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        bleScanner = adapter.bluetoothLeScanner ?: run {
            Snackbar.make(binding.root, R.string.ble_scanner_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }

        scanning = true
        binding.btnStartScan.text = getString(R.string.stop_scan)
        binding.progressBarScan.visibility = View.VISIBLE
        binding.tvScanStatus.text = getString(R.string.scanning_ble)
        binding.tvScanStatus.visibility = View.VISIBLE

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(emptyList<ScanFilter>(), settings, scanCallback)
    }

    private fun stopBleScan() {
        if (!scanning) return
        bleScanner?.stopScan(scanCallback)
        bleScanner = null
        scanning = false
        binding.btnStartScan.text = getString(R.string.start_scan)
        binding.progressBarScan.visibility = View.GONE
        val count = deviceMap.size
        binding.tvScanStatus.text = getString(R.string.scan_stopped, count)
    }

    // ---------------------------------------------------------------------------
    // Scan callback
    // ---------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val record = result.scanRecord

            val name = try {
                @Suppress("MissingPermission")
                device.name ?: ""
            } catch (e: SecurityException) {
                ""
            }

            val serviceUuids: List<String> = record?.serviceUuids
                ?.map { it.uuid.toString().lowercase(Locale.ROOT) }
                ?: emptyList()

            val isMesh = serviceUuids.any { BleDevice.isMeshServiceUuid(it) }

            val bleDevice = BleDevice(
                name = name,
                address = device.address,
                rssi = result.rssi,
                isMeshNode = isMesh,
                serviceUuids = serviceUuids
            )

            deviceMap[device.address] = bleDevice

            val sorted = deviceMap.values
                .sortedWith(compareByDescending<BleDevice> { it.isMeshNode }.thenByDescending { it.rssi })

            runOnUiThread {
                adapter.submitList(sorted.toList())
                binding.tvEmpty.visibility = View.GONE
                binding.tvScanStatus.text = getString(R.string.devices_found, sorted.size)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanning = false
                binding.btnStartScan.text = getString(R.string.start_scan)
                binding.progressBarScan.visibility = View.GONE
                Snackbar.make(
                    binding.root,
                    getString(R.string.scan_failed, errorCode),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------------

    private fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun showPermissionDeniedMessage() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(R.string.ble_permission_rationale)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
