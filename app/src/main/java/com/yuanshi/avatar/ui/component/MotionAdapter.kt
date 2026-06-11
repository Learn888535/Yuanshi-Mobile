package com.yuanshi.avatar.ui.component

import com.yuanshi.avatar.databinding.ItemMotionButtonBinding
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView


class MotionAdapter(
    private val mList: ArrayList<String>,
    private val callback: Callback
) : RecyclerView.Adapter<MotionAdapter.ItemHolder>() {

    class ItemHolder(val itemBinding: ItemMotionButtonBinding) :
        RecyclerView.ViewHolder(itemBinding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
        val itemBinding =
            ItemMotionButtonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ItemHolder(itemBinding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ItemHolder, position: Int) {
        holder.itemBinding.btnMotion.text = mList[position]
        holder.itemBinding.btnMotion.setOnClickListener {
            callback.onClick(mList[position], true)
        }
    }

    override fun getItemCount(): Int {
        return mList.size
    }

    interface Callback {
        fun onClick(name: String, now: Boolean)
    }
}