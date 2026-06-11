package com.yuanshi.avatar.ui.component

import com.yuanshi.avatar.databinding.ItemModelSelectorBinding
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView


class ModelSelectorAdapter(
    private val mList: ArrayList<String>,
    private val callback: Callback
) : RecyclerView.Adapter<ModelSelectorAdapter.ItemHolder>() {

    class ItemHolder(val itemBinding: ItemModelSelectorBinding) :
        RecyclerView.ViewHolder(itemBinding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
        val itemBinding =
            ItemModelSelectorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ItemHolder(itemBinding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ItemHolder, position: Int) {
        holder.itemBinding.tvModelUrl.text = mList[position]
        holder.itemBinding.tvModelUrl.setOnClickListener {
            callback.onClick(mList[position])
        }
    }

    override fun getItemCount(): Int {
        return mList.size
    }

    interface Callback {
        fun onClick(url: String)
    }
}