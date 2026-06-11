package com.yuanshi.avatar.engine

enum class DigitalHumanEngineType(val id: String, val displayName: String) {
    DUIX_3D("duix_3d", "DUIX 3D"),
    VIDEO_AVATAR("video_avatar", "真人视频数字人"),
    MNN_TAO_AVATAR("mnn_tao_avatar", "MNN TaoAvatar"),
    WEBRTC_AVATAR("webrtc_avatar", "实时视频数字人");

    companion object {
        fun fromId(id: String): DigitalHumanEngineType =
            values().find { it.id == id } ?: DUIX_3D
    }
}
