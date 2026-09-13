package com.muse.niimbridge.ui.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.muse.niimbot.PrinterState
import com.muse.niimbridge.R
import com.muse.niimbridge.bluetooth.BluetoothPermissions
import com.muse.niimbridge.bluetooth.DeviceScanner
import com.muse.niimbridge.databinding.FragmentConnectBinding
import com.muse.niimbridge.viewmodel.PrinterViewModel
import kotlinx.coroutines.launch

class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PrinterViewModel by activityViewModels()
    private var scanner: DeviceScanner? = null

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshGate() }
    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshGate() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = DeviceListAdapter { row -> onDeviceClicked(row) }
        binding.deviceList.adapter = adapter
        binding.deviceList.layoutManager = LinearLayoutManager(requireContext())

        binding.btnOpenSettings.setOnClickListener { BluetoothPermissions.openBluetoothSettings(requireContext()) }
        binding.btnDisconnect.setOnClickListener { viewModel.disconnect() }

        val startId = if (viewModel.transportKind.value == PrinterViewModel.TransportKind.BLE) binding.btnBle.id else binding.btnSpp.id
        binding.transportToggle.check(startId)
        binding.transportToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val kind = if (checkedId == binding.btnBle.id) PrinterViewModel.TransportKind.BLE else PrinterViewModel.TransportKind.SPP
            viewModel.setTransportKind(kind)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.printerState.collect { renderState(it) }
            }
        }

        refreshGate()
    }

    private fun onDeviceClicked(row: DeviceRow) {
        if (!row.bonded) {
            binding.unbondedWarning.visibility = View.VISIBLE
            return
        }
        binding.unbondedWarning.visibility = View.GONE
        val kind = if (binding.transportToggle.checkedButtonId == binding.btnBle.id) {
            PrinterViewModel.TransportKind.BLE
        } else {
            PrinterViewModel.TransportKind.SPP
        }
        viewModel.connect(kind, row.device)
    }

    private fun renderState(state: PrinterState) {
        binding.statusText.text = when (state) {
            is PrinterState.Disconnected -> getString(R.string.status_disconnected)
            is PrinterState.Connecting -> getString(R.string.status_connecting)
            is PrinterState.Ready -> "Connected: ${state.info.modelName} (sn ${state.info.serial})"
            is PrinterState.Printing -> "Printing ${state.page}/${state.total}"
            is PrinterState.Failed -> "Failed: ${state.reason}"
        }
        val connected = state is PrinterState.Ready || state is PrinterState.Printing
        binding.btnDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun refreshGate() {
        val adapter = viewModel.bluetoothAdapter
        when {
            adapter == null -> showGate("Bluetooth is not available on this device") {}
            !BluetoothPermissions.allGranted(requireContext()) -> showGate(getString(R.string.permission_rationale_message)) {
                requestPermissions.launch(BluetoothPermissions.REQUIRED)
            }
            !adapter.isEnabled -> showGate("Bluetooth is turned off.") {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            else -> {
                hideGate()
                setupBluetooth(adapter)
            }
        }
    }

    private fun showGate(message: String, action: () -> Unit) {
        binding.permissionCard.visibility = View.VISIBLE
        binding.gateMessage.text = message
        binding.btnGrantPermission.setOnClickListener { action() }
        binding.btnScan.isEnabled = false
    }

    private fun hideGate() {
        binding.permissionCard.visibility = View.GONE
        binding.btnScan.isEnabled = true
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth(adapter: BluetoothAdapter) {
        val scan = scanner ?: DeviceScanner(requireContext(), adapter).also { scanner = it }

        binding.btnScan.setOnClickListener { scan.startDiscovery() }

        refreshRows(scan)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scan.discovered.collect { refreshRows(scan) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshRows(scan: DeviceScanner) {
        val last = viewModel.lastDeviceAddress
        val bonded = scan.bondedDevices().map { DeviceRow(it, bonded = true, remembered = it.address == last) }
        val bondedAddresses = bonded.map { it.device.address }.toSet()
        val discovered = scan.discovered.value
            .filter { it.address !in bondedAddresses }
            .map { DeviceRow(it, bonded = it.bondState == BluetoothDevice.BOND_BONDED, remembered = it.address == last) }
        val rows = (bonded + discovered).sortedByDescending { it.remembered }
        (binding.deviceList.adapter as DeviceListAdapter).submit(rows)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanner?.dispose()
        scanner = null
        _binding = null
    }
}
