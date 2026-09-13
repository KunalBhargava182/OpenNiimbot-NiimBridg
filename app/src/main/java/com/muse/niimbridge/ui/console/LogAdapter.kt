package com.muse.niimbridge.ui.console

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.muse.niimbridge.R
import com.muse.niimbridge.databinding.ItemLogBinding
import com.muse.niimbridge.logging.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var entries: List<LogEntry> = emptyList()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** The log only ever grows by appending or resets to empty, so an incremental
     *  insert covers the common case without pulling in full DiffUtil machinery. */
    fun submit(newEntries: List<LogEntry>) {
        val old = entries
        entries = newEntries
        if (newEntries.size > old.size && newEntries.subList(0, old.size) == old) {
            notifyItemRangeInserted(old.size, newEntries.size - old.size)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(entries[position])

    override fun getItemCount() = entries.size

    inner class ViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: LogEntry) {
            val context = binding.root.context
            binding.logLine.text = "${fmt.format(Date(entry.timestampMs))}  ${entry.text}"
            binding.logLine.setTextColor(
                ContextCompat.getColor(
                    context,
                    when (entry.direction) {
                        LogEntry.Direction.TX -> R.color.console_tx
                        LogEntry.Direction.RX -> R.color.console_rx
                        LogEntry.Direction.INFO -> R.color.console_info
                    },
                ),
            )
        }
    }
}
