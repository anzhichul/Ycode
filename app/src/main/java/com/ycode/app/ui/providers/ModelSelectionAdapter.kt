package com.ycode.app.ui.providers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ycode.app.R
import com.ycode.app.databinding.ItemModelSelectBinding

class ModelSelectionAdapter(
    private val selected: MutableSet<String>,
    private val changed: () -> Unit
) : RecyclerView.Adapter<ModelSelectionAdapter.Holder>() {
    var items: List<String> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class Holder(val binding: ItemModelSelectBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ItemModelSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
        val model = items[position]
        modelName.text = model
        val checked = model in selected
        check.setBackgroundResource(if (checked) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off)
        check.alpha = if (checked) 1f else .35f
        root.setBackgroundResource(if (checked) R.drawable.bg_blue_pill else android.R.color.transparent)
        root.setOnClickListener {
            if (!selected.add(model)) selected.remove(model)
            notifyItemChanged(position)
            changed()
        }
    }
}
