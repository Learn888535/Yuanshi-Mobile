package com.yuanshi.avatar.ui.component

import com.yuanshi.avatar.R
import com.yuanshi.avatar.audio.AudioRecorder
import com.yuanshi.avatar.databinding.DialogAudioRecordBinding
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast

class AudioRecordDialog(
    private val mContext: Context,
    private val listener: Listener
) : Dialog(mContext, R.style.dialog_center) {

    private var binding: DialogAudioRecordBinding

    private var audioRecorder: AudioRecorder?=null

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogAudioRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let {
            it.setGravity(Gravity.BOTTOM)
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        binding.tvTouch.setOnTouchListener { _, event ->
            when (event?.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecord()
                }

                MotionEvent.ACTION_UP -> {
                    stopRecord()
                }
            }
            false
        }

        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }

    private fun startRecord(){
        audioRecorder = AudioRecorder(mContext, object : AudioRecorder.RecorderCallback{
            override fun onReadData(data: ByteArray, offsetInBytes: Int, length: Int) {
            }

            override fun onRecordError(code: Int, message: String) {
                binding.layoutFrame.post {
                    Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show()
                    audioRecorder?.release()
                }
            }

            override fun onFinish(path: String) {
                binding.layoutFrame.post {
                    audioRecorder?.release()
                    listener.onFinish(path)
                    dismiss()
                }
            }
        })
        audioRecorder?.start()
    }

    private fun stopRecord(){
        audioRecorder?.stop()
    }

    override fun dismiss() {
        super.dismiss()
        audioRecorder?.release()
    }

    interface Listener {
        fun onFinish(path: String)
    }

}