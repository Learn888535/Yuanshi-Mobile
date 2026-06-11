package com.yuanshi.avatar.ui.component

import com.yuanshi.avatar.R
import com.yuanshi.avatar.databinding.DialogModelSelectorBinding
import com.yuanshi.avatar.ui.component.ModelSelectorAdapter
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window


class ModelSelectorDialog(mContext: Context, val models: ArrayList<String>, private val listener: Listener) :
    Dialog(mContext, R.style.dialog_center) {

    private lateinit var binding: DialogModelSelectorBinding
    private var mAdapter: ModelSelectorAdapter?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogModelSelectorBinding.inflate(layoutInflater)
        super.setContentView(binding.root)

        mAdapter = ModelSelectorAdapter(models, object : ModelSelectorAdapter.Callback{
            override fun onClick(url: String) {
                dismiss()
                listener.onSelect(url)
            }
        })
        binding.rvModels.adapter = mAdapter

        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }

    interface Listener {
        fun onSelect(url: String)
    }
}