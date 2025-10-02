package com.dimadesu.mediasrvr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

// Items are pairs: first = interface/display name, second = URL string
class UrlAdapter(private val onUrlClick: (String) -> Unit) : ListAdapter<Pair<String, String>, UrlAdapter.UrlViewHolder>(UrlDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UrlViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_url, parent, false)
        return UrlViewHolder(view, onUrlClick)
    }

    override fun onBindViewHolder(holder: UrlViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class UrlViewHolder(itemView: View, private val onUrlClick: (String) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvIfName: TextView = itemView.findViewById(R.id.tvIfName)
        private val tvUrl: TextView = itemView.findViewById(R.id.tvUrl)

        fun bind(item: Pair<String, String>) {
            val (ifName, url) = item
            tvIfName.text = ifName
            tvUrl.text = url
            itemView.setOnClickListener {
                onUrlClick(url)
            }
        }
    }

    class UrlDiffCallback : DiffUtil.ItemCallback<Pair<String, String>>() {
        override fun areItemsTheSame(oldItem: Pair<String, String>, newItem: Pair<String, String>): Boolean {
            // treat same if url is equal
            return oldItem.second == newItem.second
        }

        override fun areContentsTheSame(oldItem: Pair<String, String>, newItem: Pair<String, String>): Boolean {
            return oldItem == newItem
        }
    }
}
