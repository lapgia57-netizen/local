package com.example.locationmap.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.locationmap.databinding.ItemLocationBinding
import com.example.locationmap.model.LocationItem
import java.text.SimpleDateFormat
import java.util.*

class LocationAdapter(
    private val onItemClick: (LocationItem) -> Unit,
    private val onDeleteClick: (LocationItem) -> Unit
) : ListAdapter<LocationItem, LocationAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemLocationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LocationItem) {
            binding.tvName.text = item.name
            binding.tvCoords.text = String.format(
                Locale.US, "%.6f, %.6f", item.latitude, item.longitude
            )
            binding.tvTime.text = dateFormat.format(Date(item.timestamp))

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LocationItem>() {
        override fun areItemsTheSame(old: LocationItem, new: LocationItem) = old.id == new.id
        override fun areContentsTheSame(old: LocationItem, new: LocationItem) = old == new
    }
}
