package com.yuanshi.avatar.service

import android.util.Log

/**
 * PCM 重采样工具
 *
 * 线性插值下采样，用于将后端返回的 24kHz PCM 下采样到 DUIX 需要的 16kHz。
 * 说明：16 / 24 = 2/3，即每 3 个输入采样产生 2 个输出采样。
 */
object PcmResampler {

    private const val TAG = "PcmResampler"

    /**
     * 16-bit mono PCM 下采样
     * @param src 源 PCM 数据 (16-bit)
     * @param srcRate 源采样率 (如 24000)
     * @param dstRate 目标采样率 (如 16000)
     * @return 下采样后的 PCM 数据
     */
    fun downsamplePcm16BitMono(src: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return src
        if (srcRate < dstRate) {
            Log.w(TAG, "srcRate=$srcRate < dstRate=$dstRate, upsampling not supported, return as-is")
            return src
        }

        val srcSamples = src.size / 2  // 16-bit = 2 bytes per sample
        val dstSamples = (srcSamples.toLong() * dstRate / srcRate).toInt()
        val dst = ByteArray(dstSamples * 2)
        val ratio = srcRate.toDouble() / dstRate.toDouble()

        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex

            if (srcIndex + 1 >= srcSamples) {
                // 最后一个采样，直接复制
                val lastSample = ((src[srcIndex * 2].toInt() and 0xFF) or
                        (src[srcIndex * 2 + 1].toInt() shl 8)).toShort()
                dst[i * 2] = (lastSample.toInt() and 0xFF).toByte()
                dst[i * 2 + 1] = (lastSample.toInt() shr 8 and 0xFF).toByte()
            } else {
                // 线性插值
                val s0 = ((src[srcIndex * 2].toInt() and 0xFF) or
                        (src[srcIndex * 2 + 1].toInt() shl 8)).toShort()
                val s1 = ((src[(srcIndex + 1) * 2].toInt() and 0xFF) or
                        (src[(srcIndex + 1) * 2 + 1].toInt() shl 8)).toShort()
                val interpolated = (s0 * (1.0 - frac) + s1 * frac).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                dst[i * 2] = (interpolated and 0xFF).toByte()
                dst[i * 2 + 1] = (interpolated shr 8 and 0xFF).toByte()
            }
        }

        return dst
    }
}
