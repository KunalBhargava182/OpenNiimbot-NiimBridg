package com.muse.niimbridge.ui.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.muse.niimbridge.databinding.ItemDeviceBinding

data class DeviceRow(val device: BluetoothDevice, val bonded: Boolean, val remembered: Boolean = false)

class DeviceListAdapter(private val onClick: (DeviceRow) -> Unit) :
    RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    private var rows: List<DeviceRow> = emptyList()

    fun submit(newRows: List<DeviceRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(rows[position])

    override fun getItemCount() = rows.size

    inner class ViewHolder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("MissingPermission")
        fun bind(row: DeviceRow) {
            val remembered = if (row.remembered) "★ last used -- " else ""
            binding.deviceName.text = row.device.name ?: "(unnamed)"
            binding.deviceAddress.text = row.device.address
            binding.deviceBondState.text = remembered + if (row.bonded) "Paired" else "Not paired -- tap to pair via settings"
            binding.root.setOnClickListener { onClick(row) }
        }
    }
}
