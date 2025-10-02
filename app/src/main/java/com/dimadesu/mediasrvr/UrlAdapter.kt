package com.dimadesu.mediasrvr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class UrlAdapter(private val onUrlClick: (String) -> Unit) : ListAdapter<String, UrlAdapter.UrlViewHolder>(UrlDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UrlViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_url, parent, false)
        return UrlViewHolder(view, onUrlClick)
    }

    override fun onBindViewHolder(holder: UrlViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class UrlViewHolder(itemView: View, private val onUrlClick: (String) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvUrl: TextView = itemView.findViewById(R.id.tvUrl)
        
        fun bind(url: String) {
            tvUrl.text = url
            itemView.setOnClickListener {
                onUrlClick(url)
            }
        }
    }

    class UrlDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}
