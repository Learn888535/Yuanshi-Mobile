package com.yuanshi.avatar

import android.app.Application
import android.util.Log
import com.yuanshi.avatar.engine.DigitalHumanEngine
import com.yuanshi.avatar.engine.DuixEngineImpl

/**
 * 原世数字人 Application
 *
 * 全局初始化：
 * - DUIX 数字人引擎
 * - 运行时配置
 */
class YuanshiApp : Application() {

    /** 数字人引擎实例（全局单例） */
    lateinit var digitalHumanEngine: DigitalHumanEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initEngine()
    }

    private fun initEngine() {
        digitalHumanEngine = DuixEngineImpl(this)
        Log.i(TAG, "原世数字人引擎初始化完成")
    }

    companion object {
        private const val TAG = "YuanshiApp"

        @Volatile
        private var instance: YuanshiApp? = null

        fun getInstance(): YuanshiApp {
            return instance ?: throw IllegalStateException("YuanshiApp 未初始化")
        }
    }
}
