package com.yuanshi.avatar.engine

import android.content.Context

object DigitalHumanEngineFactory {

    fun create(type: DigitalHumanEngineType, context: Context): DigitalHumanEngine {
        return when (type) {
            DigitalHumanEngineType.DUIX_3D -> DuixEngineImpl(context)
            DigitalHumanEngineType.VIDEO_AVATAR -> VideoAvatarEngineImpl(context)
            DigitalHumanEngineType.MNN_TAO_AVATAR -> MnnTaoAvatarEngineImpl(context)
            DigitalHumanEngineType.WEBRTC_AVATAR -> WebrtcAvatarEngineImpl(context)
        }
    }
}
