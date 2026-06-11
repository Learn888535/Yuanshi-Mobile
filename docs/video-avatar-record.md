# 视频数字人系统完整参考

> 本文档记录 Android 端视频数字人引擎的架构、工作流程、后端对接协议及已知问题。
> 适用引擎：`VIDEO_AVATAR`（真人视频数字人）、`WEBRTC_AVATAR`（实时视频数字人）。

---

## 一、引擎架构概览

Android 端通过 `DigitalHumanEngine` 抽象接口统一管理四种数字人引擎：

| 引擎类型 | 枚举值 | 实现类 | 渲染方式 | 状态 |
|---------|--------|--------|---------|------|
| DUIX 3D | `duix_3d` | `DuixEngineImpl` | NCNN 嘴型同步 + OpenGL | ✅ 完整 |
| 真人视频数字人 | `video_avatar` | `VideoAvatarEngineImpl` | TextureView + MediaPlayer | ✅ 完整 |
| 实时视频数字人 | `webrtc_avatar` | `WebrtcAvatarEngineImpl` | SurfaceView 逐帧绘制 | ⚠️ 未完整集成 |
| MNN TaoAvatar | `mnn_tao_avatar` | `MnnTaoAvatarEngineImpl` | MNN 本地推理 | ⏳ 预留 |

引擎切换由 `CallActivity.restartEngine(type: DigitalHumanEngineType)` 处理：
- 释放旧引擎 → 通过 `DigitalHumanEngineFactory.create()` 创建新引擎 → 调用 `onCreate()` → `init()` → 附加回调
- 切换 VIDEO_AVATAR 时，`pendingProviderRestart` 标志触发后端引擎模式检查
- 切换 WEBRTC_AVATAR 时无特殊处理（集成未完成）

---

## 二、VideoAvatarEngineImpl（真人视频数字人）

### 2.1 工作流程

```
VoiceSessionManager 收到 LLM 回复文本
  → engine.pendingReplyText = replyText  (VoiceSessionManager.kt:225-227)
  → engine.stopPush()
    → 停止 AudioTrack 播放
    → HTTP POST /avatar/sessions/{sid}/speak  (120s 读取超时, 130s 调用超时)
      → 后端生成 TTS 音频
      → 后端运行 Wav2Lip / MuseTalk 推理
      → 返回 video_url
    → playVideo(videoUrl)
      → 修正 URL（去掉 /api/v1 前缀处理 /static/ 路径）
      → MediaPlayer 设置 Surface → 开始播放
      → 播放完毕 → 回调 onPlayCompleted → 回到空闲状态
```

### 2.2 核心文件

| 文件 | 路径 | 关键方法 |
|------|------|---------|
| VideoAvatarEngineImpl.kt | `engine/VideoAvatarEngineImpl.kt` | `init()`, `startPush()`, `stopPush()`, `playVideo()`, `release()` |
| DigitalHumanEngine.kt | `engine/DigitalHumanEngine.kt` | 引擎抽象接口 |
| DigitalHumanEngineFactory.kt | `engine/DigitalHumanEngineFactory.kt` | `create(type, context)` 工厂 |
| DigitalHumanEngineType.kt | `engine/DigitalHumanEngineType.kt` | 引擎类型枚举 |
| VoiceSessionManager.kt | `service/VoiceSessionManager.kt` | `pendingReplyText` 设置 |

### 2.3 关键实现细节

**URL 修正逻辑**（`VideoAvatarEngineImpl.kt` 中的 `playVideo()`）：
- Android 端 baseUrl 为 `http://192.168.1.100:8000/api/v1`
- 后端返回的 video_url 为 `/static/avatar_videos/musetalk_xxx.mp4`
- 修正：`baseUrl.replace("/api/v1", "") + video_url`
- 最终访问：`http://192.168.1.100:8000/static/avatar_videos/musetalk_xxx.mp4`

**超时配置**：
- `speakHttpClient`：connectTimeout=30s, readTimeout=120s, callTimeout=130s
- 后端 Wav2Lip 推理约 99 秒，MuseTalk 约数十秒（含 TTS + 推理）
- 大段文本可能超时，建议控制回复长度

**视频显示策略**：
- FIT_CENTER（完整显示，保持比例，留黑边）
- 通过 `TextureView` 的 `layoutParams` + `Gravity.CENTER` 实现
- 不依赖 `setTransform` 矩阵（已测试不可靠）

---

## 三、WebrtcAvatarEngineImpl（实时视频数字人）

### 3.1 设计意图

通过 WebSocket 实时传输音频和视频帧，后端 MuseTalk 逐帧推理，实现低延迟的口型同步视频对话。

### 3.2 已实现的基础设施

| 方法 | 功能 | 调用方 |
|------|------|--------|
| `init()` | HTTP POST `/avatar/sessions` 创建后端会话 | `CallActivity` ✅ |
| `release()` | DELETE `/avatar/sessions/{sessionId}` 关闭会话 | `CallActivity` ✅ |
| `startPush()` | 创建 AudioTrack，播放 PCM 音频 | `VoiceSessionManager` ✅ |
| `pushPcm(data)` | 向 AudioTrack 写入 PCM 数据 | `StreamingPusher` ✅ |
| `stopPush()` | 停止 AudioTrack，显示"等待视频流..."占位 | `VoiceSessionManager` ✅ |
| `connectStream(id)` | WebSocket 连接 `ws://backend/api/v1/avatar/stream/{sessionId}` | **❌ 无人调用** |
| `sendAudio(data)` | 通过 WebSocket 发送 PCM 音频块 | **❌ 无人调用** |
| `onFrameReceived(jpeg)` | 解码 JPEG → 队列 → SurfaceView 逐帧渲染 | **❌ 无数据到达** |
| `disconnectStream()` | 关闭 WebSocket | **❌ 无人调用** |
| `stopAudio()` | 停止 AudioTrack | `VoiceSessionManager` ✅ |

### 3.3 集成缺口

`VoiceSessionManager.kt` 中以下代码仅处理 `VideoAvatarEngineImpl`：

```kotlin
// 仅在 VideoAvatarEngineImpl 时设置 pendingReplyText
if (engine is com.yuanshi.avatar.engine.VideoAvatarEngineImpl) {
    engine.pendingReplyText = replyText
}
```

对 `WebrtcAvatarEngineImpl` 没有特殊处理，导致：
1. `connectStream()` 从未被调用 → WebSocket 未连接
2. `sendAudio()` 从未被调用 → 音频仅本地播放，不发送到后端
3. `onFrameReceived()` 无数据 → 画面卡在"等待视频流..."

### 3.4 修复方案

修改 `VoiceSessionManager.kt`，在检测到引擎为 `WebrtcAvatarEngineImpl` 时：

1. **`startPush()` 中**：调用 `engine.connectStream(sessionId)` 建立 WebSocket 连接
2. **`pushPcm()` 中**：同时调用 `engine.sendAudio(pcmData)` 将音频转发到后端
3. **`stopPush()` 中**：调用 `engine.disconnectStream()` 关闭连接

### 3.5 后端流式 WebSocket 协议

详见 `care-monitor-backend/VOICE_INTERACTION.md` 第 12.4 节，简要说明：

- **端点**：`ws://host:8000/avatar/stream/{session_id}`
- **上行**：PCM 16kHz 16-bit mono 二进制音频块
- **下行**：JSON `{"type":"frame","data":"<base64_jpeg>","frame_idx":N}`
- **前提**：后端 `AVATAR_PROVIDER=musetalk`，参考视频已预处理

---

## 四、后端引擎模式

由 `.env` 中 `AVATAR_PROVIDER` 控制，Android 设置中"后端引擎模式"下拉框可切换：

| 模式 | 值 | 延迟 | 说明 |
|------|-----|------|------|
| Mock | `mock` | 无延迟 | 占位响应，不生成视频（默认）|
| Wav2Lip | `wav2lip` | ~99秒/次 | 批量 GPU 推理，完整 MP4 输出 |
| MuseTalk | `musetalk` | ~50ms/帧 | 流式 GPU 推理，推荐模式 |

核心配置（`app/services/avatar_service.py`）：

```python
AVATAR_PROVIDER=mock|wav2lip|musetalk
AVATAR_REFERENCE_VIDEO=  # 参考视频路径（必填）
```

### MuseTalk 参考视频预处理

- 首次启动时后台预处理（~2.5 分钟，750 帧 VAE 编码 + CUDA 预热 + 人脸解析）
- 预处理由 `preprocess_reference_video_async()` 在 `ThreadPoolExecutor` 中执行
- 预处理完成前 speak 请求返回 PRECOMPILE_IN_PROGRESS（不阻塞）
- 引擎切换后需重新预处理

---

## 五、Android 设置与引擎切换

### 5.1 设置项

`ListeningSettingsDialog` 维护 `SharedPreferences`：

| 设置项 | Key | 说明 |
|--------|-----|------|
| 数字人引擎 | `"setting_engine_type"` | `duix_3d` / `video_avatar` / `webrtc_avatar` |
| 后端地址 | `"setting_backend_url"` | 如 `http://192.168.1.100:8000/api/v1` |
| VAD 灵敏度 | `"setting_vad_sensitivity"` | LOW / NORMAL / HIGH |
| 模型名 | `"setting_model_name"` | 当前数字人模型 |
| 显示 WAV/PCM 按钮 | `"setting_show_play_buttons"` | 调试用 |

### 5.2 引擎切换流程

```
CallActivity.restartEngine(type: DigitalHumanEngineType)
  1. 释放旧引擎
     → oldEngine.stopPush()
     → oldEngine.stopAudio()
     → oldEngine.setCallback(null)
     → oldEngine.onPause()
     → oldEngine.onDestroy()
     → oldEngine.release()
  
  2. 清理容器
     → container.removeAllViews()
  
  3. 创建新引擎
     → newEngine = DigitalHumanEngineFactory.create(type, context)
     → newEngine.onCreate(context, container)
     → newEngine.setCallback(callback)
     → newEngine.onResume()
  
  4. 初始化
     → newEngine.init()
  
  5. 如果切换到 VIDEO_AVATAR：
     → pendingProviderRestart = true
     → 检查后端引擎模式，必要时切换
```

---

## 六、已知问题

| 问题 | 描述 | 状态 |
|------|------|------|
| WEBRTC_AVATAR 无视频流 | `connectStream()`/`sendAudio()` 未被 `VoiceSessionManager` 调用 | 待修复 |
| 首次 speak 超时 | 参考视频预处理未完成，阻塞 speak 请求 | 已修复（PRECOMPILE_IN_PROGRESS 机制）|
| 服务器重启后 404 | OLD sessionId 失效 | 已修复（speak 时自动重建）|
| 切换引擎后"未配置" | `_preprocess_event` 未重置导致状态污染 | 已修复 |
| 播报吞字 | TTS 24kHz→16kHz 重采样缺失 | 已修复（scipy resample_poly）|
| 双 /api/v1 前缀 | Android URL 拼接与 backendUrl 重复 | 已修复（兼容路由）|

---

## 七、核心文件索引

```
Android (Yuanshi-Mobile)
├── app/src/main/java/com/yuanshi/avatar/
│   ├── engine/
│   │   ├── DigitalHumanEngine.kt              ← 引擎抽象接口
│   │   ├── DigitalHumanEngineFactory.kt       ← 工厂 + 类型枚举
│   │   ├── DigitalHumanEngineType.kt          ← 引擎类型枚举
│   │   ├── VideoAvatarEngineImpl.kt           ← 视频数字人（完整）
│   │   ├── WebrtcAvatarEngineImpl.kt          ← 实时视频数字人（待集成）
│   │   ├── DuixEngineImpl.kt                  ← DUIX 3D 数字人
│   │   └── MnnTaoAvatarEngineStub.kt          ← MNN 预留
│   ├── service/
│   │   ├── VoiceSessionManager.kt             ← 设置 pendingReplyText + 音频推流
│   │   └── PushWebSocketClient.kt             ← 告警推送
│   └── ui/call/
│       └── CallActivity.kt                    ← 引擎生命周期管理 + 设置对话框

Backend (care-monitor-backend)
├── app/
│   ├── api/v1/
│   │   └── avatar.py                          ← 视频数字人 REST + WebSocket API
│   ├── services/
│   │   ├── avatar_service.py                  ← 核心服务（Mock/Wav2Lip/MuseTalk）
│   │   ├── ai_service.py                      ← TTS 生成（24kHz PCM）
│   │   └── ...
│   └── ...
├── VOICE_INTERACTION.md                       ← 完整协议文档
└── README.md                                   ← 项目概述
```
