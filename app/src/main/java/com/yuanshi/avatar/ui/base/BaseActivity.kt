package com.yuanshi.avatar.ui.base

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 原世数字人 Activity 基类
 *
 * 提供后台 Handler 线程和权限请求统一处理。
 * 对应于旧项目的 BaseActivity.java，适配为新包名和 Kotlin 风格。
 */
abstract class BaseActivity : AppCompatActivity(), Handler.Callback {

    protected val TAG: String = javaClass.name
    protected lateinit var mContext: BaseActivity
    protected lateinit var mHandler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mContext = this
        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        mHandler = Handler(handlerThread.looper, this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        mHandler.looper.quit()
    }

    override fun handleMessage(msg: Message): Boolean {
        onMessage(msg)
        return false
    }

    protected open fun onMessage(msg: Message) {
        // 子类可重写
    }

    protected fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── 权限请求 ──

    private var mRequestPermissions: Array<String>? = null
    private var mRequestPermissionCode = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val hasDeny = mRequestPermissions?.any { permission ->
            ContextCompat.checkSelfPermission(mContext, permission) !=
                    PackageManager.PERMISSION_GRANTED
        } ?: false

        if (hasDeny) {
            permissionsGet(false, mRequestPermissionCode)
        } else {
            permissionsGet(true, mRequestPermissionCode)
        }
    }

    protected fun requestPermission(permissions: Array<String>?, code: Int) {
        if (permissions == null) {
            permissionsGet(true, code)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            permissionsGet(true, code)
            return
        }
        mRequestPermissions = permissions
        mRequestPermissionCode = code
        val requestList = permissions.filter {
            ContextCompat.checkSelfPermission(mContext, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        if (requestList.isNotEmpty()) {
            permissionLauncher.launch(requestList.toTypedArray())
        } else {
            permissionsGet(true, mRequestPermissionCode)
        }
    }

    protected open fun permissionsGet(get: Boolean, code: Int) {
        // 子类可重写
    }
}
