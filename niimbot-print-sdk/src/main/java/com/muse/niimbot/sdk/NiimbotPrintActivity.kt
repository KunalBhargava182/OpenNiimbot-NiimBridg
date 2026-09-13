package com.muse.niimbot.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.muse.niimbot.PrinterState
import com.muse.niimbot.sdk.databinding.ActivityNiimbotPrintBinding
import kotlinx.coroutines.launch

/**
 * The SDK's single screen: gate on Bluetooth permission/state, connect (auto,
 * using the remembered printer when possible), preview the fixed ICF template,
 * let the coordinator pick 1 or 6 copies, print, report success/failure.
 *
 * Not part of the public API -- launch it via [NiimbotPrintSdk.createIntent].
 */
internal class NiimbotPrintActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNiimbotPrintBinding
    private val viewModel: NiimbotPrintViewModel by viewModels()

    private lateinit var studyId: String
    private lateinit var emirId: String
    private var selectedCopies = 1

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshGate() }
    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshGate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNiimbotPrintBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.niimbot_title)

        studyId = intent.getStringExtra(NiimbotPrintSdk.EXTRA_STUDY_ID).orEmpty()
        emirId = intent.getStringExtra(NiimbotPrintSdk.EXTRA_EMIR_ID).orEmpty()
        if (studyId.isBlank() || emirId.isBlank()) {
            finishWithFailure("Study ID and EMIR ID are required")
            return
        }
        binding.studyIdText.text = "Study ID: $studyId"
        binding.emirIdText.text = "EMIR ID: $emirId"
        binding.previewImage.setImageBitmap(StickerRenderer.render(studyId, emirId))

        binding.copiesToggle.check(binding.copies1.id)
        binding.copiesToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedCopies = if (checkedId == binding.copies6.id) 6 else 1
        }

        binding.btnPrint.setOnClickListener {
            binding.resultText.visibility = View.GONE
            binding.btnDone.visibility = View.GONE
            binding.btnRetry.visibility = View.GONE
            viewModel.print(studyId, emirId, selectedCopies)
        }
        binding.btnDone.setOnClickListener { finishWithSuccess() }
        binding.btnRetry.setOnClickListener {
            binding.resultText.visibility = View.GONE
            binding.btnRetry.visibility = View.GONE
        }
        binding.btnOpenBluetoothSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.printerState.collect { renderPrinterState(it) } }
                launch { viewModel.lastResult.collect { renderResult(it) } }
            }
        }

        refreshGate()
    }

    private fun refreshGate() {
        val adapter = viewModel.bluetoothAdapter
        when {
            adapter == null -> showGate(getString(R.string.niimbot_bluetooth_unavailable), null, null)
            !hasPermissions() -> showGate(getString(R.string.niimbot_permission_message), getString(R.string.niimbot_grant)) {
                requestPermissions.launch(REQUIRED_PERMISSIONS)
            }
            !adapter.isEnabled -> showGate(getString(R.string.niimbot_bluetooth_off), getString(R.string.niimbot_enable)) {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            else -> {
                binding.gateCard.visibility = View.GONE
                startConnectFlow(adapter)
            }
        }
    }

    private fun showGate(message: String, actionLabel: String?, action: (() -> Unit)?) {
        binding.gateCard.visibility = View.VISIBLE
        binding.gateMessage.text = message
        if (actionLabel != null && action != null) {
            binding.gateActionButton.visibility = View.VISIBLE
            binding.gateActionButton.text = actionLabel
            binding.gateActionButton.setOnClickListener { action() }
        } else {
            binding.gateActionButton.visibility = View.GONE
        }
    }

    private fun hasPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    private fun startConnectFlow(adapter: BluetoothAdapter) {
        val bonded = adapter.bondedDevices.toList()
        if (bonded.isEmpty()) {
            binding.deviceListContainer.visibility = View.VISIBLE
            binding.noDevicesText.visibility = View.VISIBLE
            binding.deviceButtonsContainer.removeAllViews()
            return
        }

        val remembered = viewModel.lastDeviceAddress
        val rememberedDevice = bonded.firstOrNull { it.address == remembered }
        if (rememberedDevice != null && viewModel.printerState.value is PrinterState.Disconnected) {
            viewModel.connect(rememberedDevice)
            return
        }

        showDevicePicker(bonded)
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker(devices: List<BluetoothDevice>) {
        binding.deviceListContainer.visibility = View.VISIBLE
        binding.noDevicesText.visibility = View.GONE
        binding.deviceButtonsContainer.removeAllViews()

        val sorted = devices.sortedByDescending {
            val name = it.name.orEmpty().lowercase()
            name.contains("niimbot") || name.contains("d110")
        }
        for (device in sorted) {
            val button = MaterialButton(this).apply {
                text = device.name ?: device.address
                setOnClickListener { viewModel.connect(device) }
            }
            binding.deviceButtonsContainer.addView(button)
        }
    }

    private fun renderPrinterState(state: PrinterState) {
        when (state) {
            is PrinterState.Disconnected -> {
                binding.spinner.visibility = View.GONE
                binding.printContainer.visibility = View.GONE
            }
            is PrinterState.Connecting -> {
                binding.spinner.visibility = View.VISIBLE
                binding.deviceListContainer.visibility = View.GONE
                binding.printContainer.visibility = View.GONE
                binding.statusText.text = getString(R.string.niimbot_connecting)
            }
            is PrinterState.Ready -> {
                binding.spinner.visibility = View.GONE
                binding.deviceListContainer.visibility = View.GONE
                binding.printContainer.visibility = View.VISIBLE
                binding.statusText.text = "Connected: ${state.info.modelName}"
            }
            is PrinterState.Printing -> {
                binding.spinner.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.niimbot_printing)
                binding.btnPrint.isEnabled = false
            }
            is PrinterState.Failed -> {
                binding.spinner.visibility = View.GONE
                binding.statusText.text = "Failed: ${state.reason}"
                viewModel.bluetoothAdapter?.let { showDevicePicker(retryCandidates(it)) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun retryCandidates(adapter: BluetoothAdapter): List<BluetoothDevice> = adapter.bondedDevices.toList()

    private fun renderResult(result: NiimbotPrintViewModel.PrintOutcome?) {
        binding.btnPrint.isEnabled = true
        when (result) {
            null -> return
            is NiimbotPrintViewModel.PrintOutcome.Success -> {
                binding.statusText.text = ""
                binding.resultText.visibility = View.VISIBLE
                binding.resultText.text = getString(R.string.niimbot_success)
                binding.btnDone.visibility = View.VISIBLE
            }
            is NiimbotPrintViewModel.PrintOutcome.Failure -> {
                binding.statusText.text = ""
                binding.resultText.visibility = View.VISIBLE
                binding.resultText.text = getString(R.string.niimbot_failure_prefix, result.message)
                binding.btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun finishWithSuccess() {
        val data = Intent().putExtra(NiimbotPrintSdk.EXTRA_RESULT_MESSAGE, getString(R.string.niimbot_success))
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun finishWithFailure(message: String) {
        val data = Intent().putExtra(NiimbotPrintSdk.EXTRA_RESULT_MESSAGE, message)
        setResult(Activity.RESULT_CANCELED, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) viewModel.disconnect()
    }

    companion object {
        private val REQUIRED_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
