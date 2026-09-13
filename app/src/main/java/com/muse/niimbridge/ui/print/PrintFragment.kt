package com.muse.niimbridge.ui.print

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.muse.niimbot.PrinterState
import com.muse.niimbridge.databinding.FragmentPrintBinding
import com.muse.niimbridge.viewmodel.PrinterViewModel
import kotlinx.coroutines.launch

class PrintFragment : Fragment() {

    private var _binding: FragmentPrintBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PrinterViewModel by activityViewModels()

    private val labelTypes = listOf(1, 5)
    private val rotations = listOf(0, 90, 180, 270)
    private var suppressCallbacks = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrintBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.labelTypeSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, labelTypes.map { it.toString() },
        )
        binding.rotationSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, rotations.map { "$it°" },
        )

        binding.densitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.densityValue.text = "${progress + 1}"
                if (fromUser && !suppressCallbacks) viewModel.updatePrintSettings { it.copy(density = progress + 1) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.labelTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallbacks) return
                viewModel.updatePrintSettings { it.copy(labelType = labelTypes[position]) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.rotationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallbacks) return
                viewModel.updateLabelForm { it.copy(rotationDegrees = rotations[position]) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnCopiesMinus.setOnClickListener {
            viewModel.updatePrintSettings { it.copy(copies = (it.copies - 1).coerceAtLeast(1)) }
        }
        binding.btnCopiesPlus.setOnClickListener {
            viewModel.updatePrintSettings { it.copy(copies = (it.copies + 1).coerceAtMost(20)) }
        }

        binding.btnPrintCurrent.setOnClickListener { viewModel.printCurrent() }
        binding.btnPrintAllSix.setOnClickListener { viewModel.printAllSix() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.printSettings.collect { renderSettings(it) } }
                launch { viewModel.labelForm.collect { renderRotation(it.rotationDegrees) } }
                launch { viewModel.printerState.collect { renderProgress(it) } }
                launch { viewModel.lastResult.collect { renderResult(it) } }
                launch { viewModel.batchProgress.collect { renderBatchProgress(it) } }
            }
        }
    }

    private fun renderSettings(settings: PrinterViewModel.PrintSettings) {
        suppressCallbacks = true
        binding.densitySeekBar.progress = settings.density - 1
        binding.densityValue.text = "${settings.density}"
        val typeIndex = labelTypes.indexOf(settings.labelType).coerceAtLeast(0)
        if (binding.labelTypeSpinner.selectedItemPosition != typeIndex) binding.labelTypeSpinner.setSelection(typeIndex)
        binding.copiesValue.text = "${settings.copies}"
        suppressCallbacks = false
    }

    private fun renderRotation(rotationDegrees: Int) {
        suppressCallbacks = true
        val rotIndex = rotations.indexOf(rotationDegrees).coerceAtLeast(0)
        if (binding.rotationSpinner.selectedItemPosition != rotIndex) binding.rotationSpinner.setSelection(rotIndex)
        suppressCallbacks = false
    }

    private fun renderProgress(state: PrinterState) {
        if (state is PrinterState.Printing) {
            binding.printProgress.visibility = View.VISIBLE
            binding.printProgress.progress = if (state.total > 0) (state.page * 100 / state.total) else 0
            binding.progressText.text = "Printing page ${state.page}/${state.total}"
        } else {
            binding.printProgress.visibility = View.GONE
            binding.progressText.text = ""
        }
    }

    private fun renderBatchProgress(progress: PrinterViewModel.BatchProgress?) {
        if (progress != null) {
            binding.progressText.text = "Batch: sticker ${progress.done + 1}/${progress.total} (${progress.currentType})"
        }
    }

    private fun renderResult(result: PrinterViewModel.PrintOutcome?) {
        binding.resultText.text = when (result) {
            null -> ""
            is PrinterViewModel.PrintOutcome.Success -> "✓ Success -- ${result.copies} label(s) confirmed in ${result.elapsedMs} ms"
            is PrinterViewModel.PrintOutcome.Failure -> "✗ Failed -- ${result.message}"
        }
        binding.resultText.setTextColor(
            when (result) {
                is PrinterViewModel.PrintOutcome.Success -> android.graphics.Color.rgb(0x18, 0x80, 0x38)
                is PrinterViewModel.PrintOutcome.Failure -> android.graphics.Color.rgb(0xB0, 0x00, 0x20)
                null -> android.graphics.Color.BLACK
            },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
