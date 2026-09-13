package com.muse.niimbridge.ui.console

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.muse.niimbridge.R
import com.muse.niimbridge.databinding.FragmentPacketConsoleBinding
import com.muse.niimbridge.viewmodel.PrinterViewModel
import kotlinx.coroutines.launch

class PacketConsoleFragment : Fragment() {

    private var _binding: FragmentPacketConsoleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PrinterViewModel by activityViewModels()
    private val adapter = LogAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPacketConsoleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.logList.adapter = adapter
        binding.logList.layoutManager = LinearLayoutManager(requireContext())

        binding.btnSend.setOnClickListener {
            val text = binding.rawHexInput.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                viewModel.sendRawHex(text)
                binding.rawHexInput.text?.clear()
            }
        }
        binding.btnExport.setOnClickListener {
            val file = viewModel.packetLog.exportToFile(requireContext())
            Toast.makeText(requireContext(), "Exported: ${file.name}", Toast.LENGTH_SHORT).show()
        }
        binding.btnShare.setOnClickListener { shareLog() }
        binding.btnClear.setOnClickListener { viewModel.packetLog.clear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.packetLog.entries.collect { entries ->
                    adapter.submit(entries)
                    if (entries.isNotEmpty()) binding.logList.scrollToPosition(entries.size - 1)
                }
            }
        }
    }

    private fun shareLog() {
        val file = viewModel.packetLog.exportToFile(requireContext())
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
