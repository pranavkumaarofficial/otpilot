package dev.otpilot.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.otpilot.R
import dev.otpilot.databinding.ItemMemberTabBinding
import dev.otpilot.model.FamilyMember

data class TabItem(
    val id: String,
    val name: String,
    val online: Boolean,
    val count: Int,
    val isAll: Boolean = false
)

class MemberTabAdapter(
    private val onTabClick: (String) -> Unit
) : RecyclerView.Adapter<MemberTabAdapter.ViewHolder>() {

    private val items = mutableListOf<TabItem>()
    private var selectedId = "all"

    fun setData(members: List<FamilyMember>, otpCounts: Map<String, Int>, totalCount: Int) {
        items.clear()
        items.add(TabItem("all", "All", false, totalCount, true))
        for (m in members) {
            items.add(TabItem(m.id, m.name, m.online, otpCounts[m.id] ?: 0))
        }
        notifyDataSetChanged()
    }

    fun setSelected(id: String) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMemberTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemMemberTabBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tab: TabItem) {
            val ctx = binding.root.context
            val active = tab.id == selectedId

            binding.tabName.text = tab.name

            // Background
            binding.tabRoot.setBackgroundResource(
                if (active) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            )

            // Text color
            binding.tabName.setTextColor(
                ContextCompat.getColor(ctx, if (active) R.color.on_primary else R.color.muted)
            )

            // Online dot
            if (!tab.isAll) {
                binding.onlineDot.visibility = View.VISIBLE
                binding.onlineDot.setBackgroundResource(
                    if (tab.online) {
                        if (active) R.drawable.dot_connected else R.drawable.dot_connected
                    } else {
                        R.drawable.dot_disconnected
                    }
                )
            } else {
                binding.onlineDot.visibility = View.GONE
            }

            // Count badge
            if (tab.count > 0) {
                binding.countBadge.visibility = View.VISIBLE
                binding.countBadge.text = tab.count.toString()
            } else {
                binding.countBadge.visibility = View.GONE
            }

            binding.tabRoot.setOnClickListener {
                onTabClick(tab.id)
            }
        }
    }
}
