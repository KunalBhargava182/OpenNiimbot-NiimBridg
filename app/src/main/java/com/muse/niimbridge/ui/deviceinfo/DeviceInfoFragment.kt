package com.muse.niimbridge.ui.deviceinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.muse.niimbot.DeviceInfo
import com.muse.niimbot.Opcodes
import com.muse.niimbot.RfidInfo
import com.muse.niimbridge.R
import com.muse.niimbridge.databinding.FragmentDeviceInfoBinding
import com.muse.niimbridge.viewmodel.PrinterViewModel
import kotlinx.coroutines.launch

class DeviceInfoFragment : Fragment() {

    private var _binding: FragmentDeviceInfoBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PrinterViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeviceInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRefresh.setOnClickListener {
            viewModel.refreshDeviceInfo()
            viewModel.refreshRfid()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.deviceInfo.collect { renderDeviceInfo(it) } }
                launch { viewModel.rfidInfo.collect { renderRfid(it) } }
            }
        }
    }

    private fun renderDeviceInfo(info: DeviceInfo?) {
        val connected = info != null
        binding.notConnectedText.visibility = if (connected) View.GONE else View.VISIBLE
        binding.infoGroup.visibility = if (connected) View.VISIBLE else View.GONE
        binding.btnRefresh.isEnabled = connected
        info ?: return

        binding.modelIdValue.text = "${info.modelId} (${info.modelName})"
        val mismatch = info.modelId != Opcodes.MODEL_ID_D110_M
        binding.modelWarning.visibility = if (mismatch) View.VISIBLE else View.GONE
        binding.firmwareValue.text = info.softVersion
        binding.hardwareValue.text = info.hardVersion
        binding.serialValue.text = info.serial
        binding.batteryValue.text = "${info.battery}"
    }

    private fun renderRfid(rfid: RfidInfo?) {
        if (rfid == null) {
            binding.rfidRemainingValue.text = ""
            binding.rfidDetailValue.text = ""
            return
        }
        binding.rfidRemainingValue.text = getString(R.string.rfid_remaining_label, rfid.remaining)
        binding.rfidDetailValue.text =
            "barcode=${rfid.barcode}  serial=${rfid.serial}\ntotal=${rfid.totalLabels}  used=${rfid.usedLabels}  type=${rfid.type}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
