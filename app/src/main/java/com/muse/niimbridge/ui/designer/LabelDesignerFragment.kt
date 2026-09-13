package com.muse.niimbridge.ui.designer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.muse.niimbridge.databinding.FragmentLabelDesignerBinding
import com.muse.niimbridge.render.LabelFont
import com.muse.niimbridge.render.LabelRenderer
import com.muse.niimbridge.render.StickerType
import com.muse.niimbridge.render.TextAlignment
import com.muse.niimbridge.viewmodel.PrinterViewModel
import kotlinx.coroutines.launch

class LabelDesignerFragment : Fragment() {

    private var _binding: FragmentLabelDesignerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PrinterViewModel by activityViewModels()

    private val rotations = listOf(0, 90, 180, 270)
    private val fonts = LabelFont.entries
    private var suppressCallbacks = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLabelDesignerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.stickerTypeSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, StickerType.entries.map { it.name },
        )
        binding.rotationSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, rotations.map { "$it°" },
        )
        binding.fontSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, fonts.map { it.displayName },
        )

        binding.studyIdInput.doAfterTextChanged { text ->
            if (suppressCallbacks) return@doAfterTextChanged
            viewModel.updateLabelForm { it.copy(studyId = text?.toString().orEmpty()) }
        }
        binding.emirIdInput.doAfterTextChanged { text ->
            if (suppressCallbacks) return@doAfterTextChanged
            viewModel.updateLabelForm { it.copy(emirId = text?.toString().orEmpty()) }
        }
        binding.stickerTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallbacks) return
                viewModel.updateLabelForm { it.copy(stickerType = StickerType.entries[position]) }
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
        binding.fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallbacks) return
                viewModel.updateLabelForm { it.copy(style = it.style.copy(font = fonts[position])) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.alignmentToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (suppressCallbacks || !isChecked) return@addOnButtonCheckedListener
            val alignment = when (checkedId) {
                binding.alignCenter.id -> TextAlignment.CENTER
                binding.alignRight.id -> TextAlignment.RIGHT
                else -> TextAlignment.LEFT
            }
            viewModel.updateLabelForm { it.copy(style = it.style.copy(alignment = alignment)) }
        }

        binding.styleToggle.addOnButtonCheckedListener { group, _, _ ->
            if (suppressCallbacks) return@addOnButtonCheckedListener
            viewModel.updateLabelForm {
                it.copy(
                    style = it.style.copy(
                        bold = group.checkedButtonIds.contains(binding.styleBold.id),
                        italic = group.checkedButtonIds.contains(binding.styleItalic.id),
                        underline = group.checkedButtonIds.contains(binding.styleUnderline.id),
                    ),
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.labelForm.collect { renderForm(it) }
            }
        }
    }

    private fun renderForm(form: PrinterViewModel.LabelForm) {
        suppressCallbacks = true
        if (binding.studyIdInput.text?.toString() != form.studyId) binding.studyIdInput.setText(form.studyId)
        if (binding.emirIdInput.text?.toString() != form.emirId) binding.emirIdInput.setText(form.emirId)

        val typeIndex = StickerType.entries.indexOf(form.stickerType)
        if (binding.stickerTypeSpinner.selectedItemPosition != typeIndex) binding.stickerTypeSpinner.setSelection(typeIndex)

        val rotIndex = rotations.indexOf(form.rotationDegrees).coerceAtLeast(0)
        if (binding.rotationSpinner.selectedItemPosition != rotIndex) binding.rotationSpinner.setSelection(rotIndex)

        val fontIndex = fonts.indexOf(form.style.font).coerceAtLeast(0)
        if (binding.fontSpinner.selectedItemPosition != fontIndex) binding.fontSpinner.setSelection(fontIndex)

        val alignId = when (form.style.alignment) {
            TextAlignment.LEFT -> binding.alignLeft.id
            TextAlignment.CENTER -> binding.alignCenter.id
            TextAlignment.RIGHT -> binding.alignRight.id
        }
        if (binding.alignmentToggle.checkedButtonId != alignId) binding.alignmentToggle.check(alignId)

        binding.styleBold.isChecked = form.style.bold
        binding.styleItalic.isChecked = form.style.italic
        binding.styleUnderline.isChecked = form.style.underline
        suppressCallbacks = false

        val bitmap = LabelRenderer.render(viewModel.currentStickerData(), form.style, form.rotationDegrees)
        binding.bitmapPreview.setBitmap(bitmap)
        binding.dimensionReadout.text = "${bitmap.width} × ${bitmap.height} px"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
