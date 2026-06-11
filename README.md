# 原世数字人 (Yuanshi Digital Human)

**原世数字人** 是一款基于 Android 平台的 AI 数字人语音交互应用，集成 **DUIX SDK** 数字人渲染引擎 + **阿里云 DashScope** 大模型服务，实现实时流式语音对话、口型同步、设备控制等功能。

配套后端 **Care Monitor Backend** 提供 AI 服务（ASR/LLM/TTS）、视频监控、跌倒检测、Web 管理后台等能力。

---

## 系统架构

```
┌─ Android App (Yuanshi-Mobile) ──────────────────────────────────────┐
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                  DigitalHumanEngine (抽象接口)                         │   │
│  │  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────────┐  │   │
│  │  │  DuixEngineImpl  │  │VideoAvatarEngine │  │WebrtcAvatarEngine │  │   │
│  │  │  (DUIX 3D 数字人) │  │Impl (视频数字人)   │  │Impl (实时视频数字人)│  │   │
│  │  │  NCNN 嘴型同步    │  │TextureView +     │  │WebSocket 流式视频 │  │   │
│  │  │  OpenGL 渲染     │  │MediaPlayer 播放   │  │ ⚠️ 未完整集成     │  │   │
│  │  └────────┬────────┘  └────────┬─────────┘  └────────┬──────────┘  │   │
│  └───────────┼────────────────────┼─────────────────────┼──────────────┘   │
│              │                    │                                   │
│  mic → PCM 16kHz ── HTTP POST / 流式 WebSocket ─────────┐          │
│                                                          │          │
│  PushWebSocketClient ← /ws/push (告警推送 + TTS 播报)      │          │
│  DeviceController → open_app / close_app / set_volume     │          │
│                                                          │          │
│  三模式录音: 按住说话 / 持续监听 / 唤醒词"原宝"             │          │
└──────────────────────────┬───────────────────────────────┼──────────┘
                           │                               │
              HTTP / WebSocket / StreamingResponse         │
                           │                               │
┌─ Care Monitor Backend (FastAPI) ────────────────────────┘──────────┐
│                                                                      │
│  ASR (qwen3-asr-flash) → LLM (qwen-turbo) → TTS                     │
│  Function Calling: 天气 / 新闻 / 时间 / 设备控制                      │
│                                                                      │
│  视频分析: OpenCV HOG + 阿里云视觉 (qwen3-vl-plus)                    │
│  跌倒检测 / 隐私场景识别 / 警报推送                                    │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │              AvatarService（视频数字人服务）                    │   │
│  │  AVATAR_PROVIDER=mock → 占位响应（默认）                      │   │
│  │  AVATAR_PROVIDER=wav2lip → Wav2Lip 口型同步视频生成            │   │
│  │  参考视频 + TTS 音频 → 1920×1080 H.264+AAC 视频输出            │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  SQLite 持久化 / Web 管理后台 / TV 大屏                               │
│  企业微信/钉钉推送 / 定时播报                                          │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 多引擎数字人架构

Android 端采用 **依赖反转** 设计，业务层（`VoiceSessionManager`、`PushWebSocketClient`、`CallActivity`）只依赖抽象接口 `DigitalHumanEngine`，不依赖具体引擎实现。

### 引擎接口 DigitalHumanEngine

```kotlin
interface DigitalHumanEngine {
    // 生命周期
    fun onCreate(context: Context, container: FrameLayout)
    fun onResume()
    fun onPause()
    fun onDestroy()

    // 音频推送
    fun startPush()
    fun pushPcm(data: ByteArray?, offset: Int, length: Int)
    fun stopPush()
    fun stopAudio()

    // 动作
    fun startRandomMotion(loop: Boolean = true)
    fun setMotion(motionName: String, now: Boolean = true)
    fun getSupportedMotions(): List<String>

    // 回调
    fun setCallback(callback: DigitalHumanCallback?)
}
```

### 引擎类型与切换

| 引擎类型 | 枚举值 | 说明 | 状态 |
|---------|--------|------|------|
| **DUIX 3D** | `duix_3d` | DUIX SDK，NCNN 嘴型同步 + OpenGL 渲染（默认） | ✅ 可用 |
| **真人视频数字人** | `video_avatar` | TextureView + MediaPlayer 播放后端生成的真人口型同步视频 | ✅ 可用 |
| **实时视频数字人** | `webrtc_avatar` | WebSocket 流式视频帧传输，后端 MuseTalk 实时生成口型同步视频 | ⚠️ 未完整集成 |
| **MNN TaoAvatar** | `mnn_tao_avatar` | MNN 推理引擎驱动的本地真人数字人（预留） | ⏳ 预留 |

### 引擎工厂

```kotlin
DigitalHumanEngineFactory.create(
    context = this,
    type = DigitalHumanEngineType.DUIX_3D  // 或 VIDEO_AVATAR / WEBRTC_AVATAR
)
```

- 默认引擎通过配置选择，支持运行时切换
- 切换引擎只需替换工厂参数，无需修改业务代码
- 视频引擎不可用时自动回退到 DUIX 3D
- **WEBRTC_AVATAR（实时视频数字人）**：引擎框架已就绪（`WebrtcAvatarEngineImpl`），但 `VoiceSessionManager` 尚未调用 `connectStream()`/`sendAudio()`，目前切换后音频从手机扬声器播放但无视频流。详见下方说明。

### 架构原理

```
业务层 (CallActivity / VoiceSessionManager / PushWebSocketClient)
    │ 只依赖 DigitalHumanEngine 接口
    ▼
DigitalHumanEngine (抽象接口)
    │ 工厂创建具体实现
    ▼
DuixEngineImpl       VideoAvatarEngineImpl      WebrtcAvatarEngineImpl      MnnTaoAvatarEngineStub
(DUIX SDK 3D)        (服务端 Wav2Lip 视频)       (WebSocket 流式视频)         (MNN 本地真人引擎)
                     ✅ 可用                     ⚠️ 未完整集成               ⏳ 预留
```

### 数据流对比

| 环节 | DUIX 3D 引擎 | 视频数字人引擎 | 实时视频数字人引擎 |
|------|-------------|---------------|-------------------|
| 渲染方式 | NCNN 嘴型同步 + OpenGL | TextureView + MediaPlayer | SurfaceView 逐帧绘制 |
| 音频来源 | 后端 TTS → WebSocket → PCM 块 | 后端 TTS → Wav2Lip/MuseTalk → 视频文件 | 本地 AudioTrack 播放 PCM |
| 视频来源 | 本地 GPU 实时渲染 | 后端生成 MP4 文件 → HTTP 下载 | WebSocket 流式 JPEG 帧 |
| 驱动方式 | `pushPcm()` 逐帧推送到 DUIX SDK | `playVideo(url)` 播放完整视频 | `onFrameReceived(jpeg)` 逐帧绘制 |
| 口型同步 | DUIX SDK 实时运算 | Wav2Lip/MuseTalk 预渲染到视频帧 | MuseTalk 实时推理 |
| 延迟 | 低（流式） | 较高（需等待完整视频生成） | 中（流式帧，50ms/帧） |
| 后端要求 | 无 | Wav2Lip(~99s) 或 MuseTalk(~50ms/帧) | MuseTalk（需 WebSocket 流式端点） |
| 集成状态 | ✅ 完整 | ✅ 完整 | ⚠️ 框架就绪，集成未完成 |
| 适合场景 | 实时对话 | 固定内容播报、告警播报 | 实时对话（待集成） |

### ⚠️ 实时视频数字人引擎（WEBRTC_AVATAR）集成状态

`WebrtcAvatarEngineImpl` 已实现以下基础设施：

| 组件 | 状态 | 说明 |
|------|------|------|
| `init()` | ✅ 完成 | 通过 HTTP POST `/avatar/sessions` 创建后端会话 |
| `release()` | ✅ 完成 | 通过 DELETE `/avatar/sessions/{sessionId}` 关闭会话 |
| `startPush()` | ✅ 完成 | 创建本地 AudioTrack 播放 PCM 音频 |
| `pushPcm()` | ✅ 完成 | 向本地 AudioTrack 写入 PCM 数据 |
| `stopPush()` | ✅ 完成 | 停止 AudioTrack，显示"等待视频流..."占位 |
| `connectStream(streamSessionId)` | ✅ 完成 | WebSocket 连接 `ws://backend/api/v1/avatar/stream/{sessionId}` |
| `sendAudio(audioData)` | ✅ 完成 | 通过 WebSocket 发送 PCM 音频块 |
| `onFrameReceived(jpegData)` | ✅ 完成 | 解码 JPEG → 队列 → SurfaceView 逐帧渲染 |
| `disconnectStream()` | ✅ 完成 | 关闭 WebSocket |
| **VoiceSessionManager 调用 `connectStream()`** | ❌ **未实现** | 切换至 WEBRTC_AVATAR 后不会启动视频流 |
| **VoiceSessionManager 调用 `sendAudio()`** | ❌ **未实现** | 音频仅通过本地 AudioTrack 播放，不会发送到后端 |

**现象**：切换到"实时视频数字人引擎"后，音频通过手机扬声器正常播放，但画面显示"等待视频流..."占位，无视频流。

**修复方案**：修改 `VoiceSessionManager.kt`，在检测到引擎为 `WebrtcAvatarEngineImpl` 时：
1. `startPush()` → 调用 `connectStream()` + 启动音频发送循环
2. `pushPcm()` → 同时调用 `sendAudio()` 将 PCM 转发到后端
3. `stopPush()` → 调用 `disconnectStream()`

---

## 核心功能

### Android 端

| 功能 | 说明 |
|------|------|
| 🎤 **三模式录音** | 按住说话(Push-to-Talk) / 持续监听(Toggle) / 唤醒词(说"原宝") |
| 🔄 **流式语音交互** | 边录边发（PCM 分块）→ 后端实时响应 → 边收边播，低延迟 |
| 🤖 **多引擎数字人** | 支持 DUIX 3D（默认）/ 视频数字人 / 实时视频数字人 / MNN TaoAvatar 四种引擎，业务层统一接口；WEBRTC_AVATAR 框架就绪但集成未完成 |
| 🎮 **设备控制** | 语音打开/关闭应用、调节音量、媒体控制、拍照（Function Calling） |
| 📺 **画中画(PiP)** | 启动其他应用时数字人自动缩小为悬浮窗 |
| 🔔 **告警推送** | 接收后端跌倒检测警报 + TTS 语音播报 |
| 📋 **模型管理** | 内置模型 + ADB 添加 + APK 内删除 |
| 🎵 **远程音频播放** | WAV/PCM 按钮从服务器下载并播放，支持本地文件回退 |

### 后端 (Care Monitor)

| 功能 | 说明 |
|------|------|
| 🤖 **AI 服务** | ASR 语音识别 / LLM 对话 / TTS 语音合成（阿里云 DashScope） |
| 🎬 **视频数字人** | Wav2Lip 口型同步视频生成，参考视频 + TTS 音频 → 真人说话视频 |
| 📷 **视频分析** | 多摄像头（USB/RTSP/IP Webcam）跌倒检测 + 场景识别 |
| 🚨 **警报系统** | 跌倒检测 → 去重 → 冷却 → 多渠道推送（WS/企微/钉钉/短信） |
| 📊 **管理后台** | 概览/对话/设备/摄像头/警报/日志/配置/系统监控/审计/维护 SPA |
| 📺 **数据大屏** | `/screen` TV 投屏只读展示，10s 自动刷新 |
| ⏰ **定时播报** | 每日定时问候/天气播报/自定义文本，自动推到数字人播报 |
| 🔧 **系统维护** | 数据库备份恢复 / 日志轮转 / 老旧数据自动清理 |

---

## 项目结构

```
Yuanshi-Mobile/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── duix/model/         ← 预置数字人模型文件
│   │   │       ├── gj_dh_res/       ← 基础配置（必需）
│   │   │       ├── tmp/             ← 模型标记文件
│   │   │       └── bendi3_20240518/ ← 默认模型（本迪3）
│   │   ├── java/com/yuanshi/avatar/
│   │   │   ├── engine/              ← 数字人引擎抽象层
│   │   │   │   ├── DigitalHumanEngine.kt    ← 引擎接口
│   │   │   │   ├── DigitalHumanEngineFactory.kt ← 引擎工厂
│   │   │   │   ├── DigitalHumanEngineType.kt ← 引擎类型枚举
│   │   │   │   ├── DuixEngineImpl.kt         ← DUIX SDK 3D 实现
│   │   │   │   ├── VideoAvatarEngineImpl.kt  ← 视频数字人实现
│   │   │   │   ├── WebrtcAvatarEngineImpl.kt ← 实时视频数字人实现(⚠️未完整集成)
│   │   │   │   └── MnnTaoAvatarEngineStub.kt ← MNN 真人引擎桩
│   │   │   ├── service/             ← 核心服务
│   │   │   │   ├── VoiceSessionManager.kt ← 语音交互 + 设备控制
│   │   │   │   ├── WakeWordDetector.kt    ← VAD+后端ASR唤醒检测
│   │   │   │   ├── PushWebSocketClient.kt ← 推送WS客户端
│   │   │   │   ├── PcmResampler.kt        ← 音频重采样
│   │   │   │   └── ModelAssetInstaller.kt ← 模型安装器
│   │   │   ├── audio/
│   │   │   │   └── AudioRecorder.java     ← 原生录音
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt        ← 主页（模型列表）
│   │   │   │   ├── call/CallActivity.kt   ← 数字人交互页
│   │   │   │   ├── component/             ← UI组件（对话框等）
│   │   │   │   └── settings/              ← 设置
│   │   │   └── YuanshiApp.kt             ← Application
│   │   └── res/
│   │       ├── layout/                    ← XML布局
│   │       ├── drawable/                  ← 图形资源
│   │       └── values/                    ← 字符串/颜色/主题
│   ├── build.gradle                       ← 构建配置
│   └── ...
├── duix-sdk/                              ← DUIX SDK 库
└── README.md                              ← 本文件

care-monitor-backend/                      ← 后端服务（独立仓库）
├── app/
│   ├── main.py                            ← FastAPI 入口
│   ├── config.py                          ← 配置（.env）
│   ├── api/v1/                            ← API端点
│   ├── services/                          ← 业务逻辑
│   ├── models/                            ← 数据模型
│   └── auth.py                            ← JWT认证
├── static/                                ← 前端 SPA
│   ├── index.html                         ← 管理后台
│   ├── screen.html                        ← TV 大屏
│   ├── css/style.css
│   └── js/app.js
├── README.md                              ← 后端文档
└── .env                                   ← 环境配置
```

---

## Android 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1.1+) 或更新版本
- Gradle 8.x
- Android SDK 34+
- JDK 17
- 一台 Android 8.0+ (API 26+) 设备

### 构建 & 安装

```bash
# 1. 克隆项目
git clone <repository-url>
cd Yuanshi-Mobile

# 2. 配置后端地址（在 local.properties 中）
echo "backend.baseUrl=http://192.168.1.100:8000/api/v1" >> local.properties

# 3. 使用 Android Studio 打开项目
#    File → Open → 选择 Yuanshi-Mobile 目录

# 4. 连接设备，点击 Run 或：
./gradlew installDebug
```

### 首次使用

1. 安装 APK 后打开应用
2. 应用会自动从 assets 安装模型文件（约 132MB+）
3. 等待安装完成 → 主页显示可用模型列表
4. 点击模型 → 进入数字人交互页
5. 在设置中配置后端地址（齿轮图标 → 后端地址）
6. 选择录音模式开始对话

---

## 录音模式详解

| 模式 | 触发方式 | 自动循环 | 适用场景 |
|------|---------|---------|---------|
| **按住说话** | 按住按钮录音 → 松开发送 | 否（回到 IDLE） | 安静环境，精确控制 |
| **持续监听** | 点击进入监听 → 说话自动发送 | 是 | 免手持对话 |
| **唤醒词模式** | 说"**原宝**"唤醒 → 说话 | 是（播完继续监听） | 全程免操作 |

### 唤醒词模式的状态机

```
WAKE_WORD (VAD + 后端 ASR)
    ↓ 说"原宝" → ASR 匹配
播报"我在，有什么可以帮助您的?"
    ↓ TTS 播完
LISTENING (录制用户指令，流式发送 PCM)
    ↓ VAD 静音 1.0s
THINKING (等待后端 ASR+LLM+TTS)
    ↓
SPEAKING (播放 TTS 音频，嘴型同步)
    ↓ TTS 播完
WAKE_WORD (回到唤醒监听，自动循环)
```

### VAD 参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 采样率 | 16kHz 16-bit MONO | AudioRecord 配置 |
| 帧大小 | 640 bytes (20ms) | 每帧处理 |
| 语音阈值 | ≥8 帧确认（NORMAL）/ 12 帧（LOW）/ 5 帧（HIGH） | 防误触 |
| 静音超时 | 1.0s（NORMAL）/ 1.2s（LOW）/ 0.7s（HIGH） | 自动停止录音 |
| 自适应阈值 | `max(minRms, backgroundRms×2+200)` | 背景噪音来自 VAD 忽略期采集，每帧 EMA 更新 |
| VAD 忽略期 | 前 800ms | 录音后先采集背景噪音，不检测 VAD（防 TTS 回声） |
| 语音帧过滤 | RMS>250 直接跳过；RMS>150+ZCR>0.035 跳过 | 忽略期内防止用户说话污染背景 RMS |
| 最长录音 | 15s | 防无限录制 |

> 灵敏度三档（LOW/NORMAL/HIGH）在 App 设置→VAD 灵敏度中切换。

---

## WAV/PCM 音频播放按钮

### 功能概述

数字人交互界面底部有两个特殊按钮：**播放WAV文件** 和 **播放PCM流**，用于播放自定义音频文件（非 TTS 语音）。

### 按钮显示控制

- **默认隐藏**，在设置对话框（齿轮图标）中勾选 **"显示 WAV/PCM 按钮"** 后显示
- 设置保存在 SharedPreferences，重启后保持

### 播放流程

```
点击 WAV/PCM 按钮
  → 查询服务器音频文件列表 (GET /api/v1/dashboard/audio-files)
  ├─ 有文件 → 弹出文件选择对话框
  │   └─ 选择文件 → 下载到本地 → 播放
  └─ 无文件 → 弹出提示
      └─ "是否从手机本地选择文件播放？"
          ├─ 确定 → 打开系统文件选择器
          └─ 取消 → 关闭
```

### 播放方式

| 按钮 | 播放方式 | 文件格式要求 |
|------|---------|-------------|
| **播放WAV文件** | `duix.playAudio(filePath)` | 16kHz/16bit/单声道 WAV（后端上传时自动转换） |
| **播放PCM流** | `duix.pushPcm()` 逐块推送 | 裸 PCM 16kHz/16bit/单声道 |

### 服务器端要求

WAV 文件上传到管理后台后，后端自动转换为 DUIX 标准格式（16kHz/16bit/单声道），无需手动处理。

---

## 模型管理

### DUIX 模型文件结构

一个完整的数字人模型包含以下文件（在 `{externalFilesDir}/duix/model/<模型名>/` 目录下）：

| 文件 | 说明 |
|------|------|
| `bbox.j` | 人脸检测框数据 |
| `config.j` | 模型配置 |
| `dh_model.b` | 数字人模型权重（二进制） |
| `dh_model.p` | 数字人模型结构 |
| `weight_168u.b` | 权重文件（168个关键点） |
| `pha/` | 背景抠图序列帧(.sij) |
| `raw_jpgs/` | 训练素材序列帧(.sij) |
| `raw_sg/` | 语义引导序列帧(.sij) |

此外，基础配置目录 `gj_dh_res/` 包含：

| 文件 | 说明 |
|------|------|
| `alpha_model.b` / `alpha_model.p` | 阿尔法模型 |
| `cacert.p` | 证书文件 |
| `weight_168u.b` | 168点权重 |
| `wenet.o` | 语音识别模型 |

### 添加模型

#### 方式 1：预置到 APK（推荐，重新打包）

1. 把完整的模型文件夹放入 `app/src/main/assets/duix/model/`：
```
app/src/main/assets/duix/model/
├── gj_dh_res/              ← 基础配置（必需，已存在）
├── tmp/
│   ├── gj_dh_res           ← 基础配置标记（已存在）
│   └── <新模型名>           ← 新建空文件作为标记
└── <新模型名>/              ← 模型目录
    ├── bbox.j
    ├── config.j
    ├── dh_model.b
    ├── dh_model.p
    ├── weight_168u.b
    ├── pha/
    ├── raw_jpgs/
    └── raw_sg/
```

2. 重新编译 APK → 安装 → 首次启动自动安装

#### 方式 2：ADB 推送（调试用，不重新打包）

```bash
# 1. 推送模型文件
adb push <模型文件夹> /storage/emulated/0/Android/data/com.yuanshi.avatar/files/duix/model/

# 2. 创建标记文件
adb shell touch /storage/emulated/0/Android/data/com.yuanshi.avatar/files/duix/model/tmp/<模型名>

# 3. 回到 App 主页，自动刷新显示新模型
```

#### 方式 3：PC 文件传输

```bash
# 1. 连接手机到电脑，开启文件传输模式
# 2. 打开内部存储 → Android/data/com.yuanshi.avatar/files/duix/model/
# 3. 放入模型文件夹 + 在 tmp/ 下创建同名标记文件
# 4. 回到 App 主页自动刷新
```

### 删除模型

在主页模型列表中，每项右侧有 **"删除"** 按钮 → 确认对话框 → 删除模型目录 + 标记文件。

### 模型来源说明

> **注意**：DUIX 数字人模型是 **硅基智能（GuiJi AI）** 的专有资产。
> 编译后的模型文件（`bbox.j`, `config.j`, `dh_model.b` 等）由 DUIX SDK 的专有编译器生成，不对外公开。
> 目前项目中预置的模型来自 DUIX 官方测试 SDK。如需更多模型，请联系硅基智能获取授权。

---

## 后端部署

> 详细文档见 [care-monitor-backend/README.md](care-monitor-backend/README.md)

### 快速启动

```bash
cd care-monitor-backend

# 1. 安装依赖
pip install -r requirements.txt

# 2. 配置环境变量（编辑 .env）
#    DASHSCOPE_API_KEY=your-key-here

# 3. 启动服务
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### 管理后台

启动后端后打开 http://localhost:8000/dashboard

- 默认账号: `admin` / `admin`
- 管理摄像头、警报、对话记录、配置等

### TV 数据大屏

http://localhost:8000/screen — 只读展示，无需登录，10s 自动刷新

---

## 配置说明

### Android 端 (local.properties)

```properties
# 后端地址（如使用后端模式）
backend.baseUrl=http://192.168.1.100:8000/api/v1

# 直连模式（可选，绕过后端直接调用阿里云）
dashscope.apiKey=sk-xxxxxxxx
dashscope.baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1
dashscope.ttsWsUrl=wss://dashscope.aliyuncs.com/api-ws/v1/realtime
```

### 后端 (.env)

```env
# 阿里云 API Key（必填）
DASHSCOPE_API_KEY=sk-xxxxxxxx

# AI 模式
USE_MOCK_AI=false           # true=模拟数据，false=真实AI
ENABLE_ALI_VISION=true      # AI 视觉检测开关

# 摄像头
CAMERA_CONFIGS=["0"]        # USB摄像头索引 或 RTSP URL
CAMERAS=[{"id":"camera_1","source":"0","location":"客厅"}]

# 安全（生产环境务必修改）
SECRET_KEY=your-secret-key-here-change-in-production
```

---

## 性能优化

### 语音延迟优化

| 阶段 | 措施 | 效果 |
|------|------|------|
| 初始 | 无优化 | 最长 42s TTS |
| 编码 | 严格 system prompt + max_tokens=120 | 降至 2-4s |
| 流式 | NDJSON 流式 + 过渡音频 | 消除静默期 |
| 推帧 | 去掉 Thread.sleep，全速推送 | 嘴型同步正常工作 |

### 关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| TTS max_tokens | 120 | 平衡完整性和速度 |
| TTS chunk size | 50 字符 | 后端流式切割 |
| 音频采样率 | 后端 24kHz → 下采样 16kHz | DUIX 需求 |
| 推帧粒度 | 3200 字节/帧 | 减少 GC 压力 |

---

## 常见问题

### Q: App 闪退怎么办？
A: 先检查是否安装了最新版。闪退通常是因为模型文件损坏或缺失。尝试删除 `Android/data/com.yuanshi.avatar/files/duix/model/` 目录后重新打开 App 重新安装。

### Q: 唤醒词模式不响应？
A: 检查以下三点：
1. Android 端设置 → 后端地址是否正确（如 `http://192.168.1.100:8000/api/v1`）
2. 后端是否运行且 `/voice/asr-wake` 端点可访问
3. 确认后端的 DashScope API Key 配置正确

### Q: 数字人没有声音或嘴型不同步？
A: 前往管理后台配置页 → 确保"数字人告警语音播报"开关开启。如果嘴型不同步，尝试重启 App。

### Q: WebSocket 连接不上后端？
A: 检查：
1. 手机和服务器是否在同一网络
2. 后端地址是否正确（IP + 端口）
3. 服务器防火墙是否开放了 8000 端口

### Q: 如何用安卓手机做摄像头？
A: 安装 **IP Webcam** 应用 → 启动后获取 RTSP 地址 → 在管理后台添加摄像头，source 填入 `rtsp://手机IP:8554/live`。

### Q: 删除模型后还能恢复吗？
A: 不能。删除操作会永久删除模型目录和标记文件。如需恢复，需要用 ADB 或重新安装 APK。

### Q: 数据大屏如何全屏？
A: 浏览器打开 `/screen` 页面后，按 F11 进入全屏（或使用电视/投影仪的遥控器全屏功能）。

---

## 技术栈

### Android
- **语言**: Kotlin + Java
- **数字人引擎**: DUIX SDK（NCNN 嘴型同步 + OpenGL 渲染）
- **网络**: OkHttp 4.x（HTTP + WebSocket）
- **音频**: AudioRecord / AudioTrack
- **UI**: ConstraintLayout / RecyclerView / Material Design
- **相机**: CameraX
- **最低 API**: 26 (Android 8.0)

### 后端
- **框架**: FastAPI (Python)
- **AI 服务**: 阿里云 DashScope（qwen3-asr-flash, qwen-turbo, qwen3-tts-flash-realtime）
- **视频分析**: OpenCV + 阿里云视觉模型 (qwen3-vl-plus)
- **数据持久化**: SQLAlchemy + SQLite
- **前端**: 纯 HTML/CSS/JS SPA + Chart.js
- **部署**: uvicorn + Docker

---

## 许可

本项目为内部项目。DUIX SDK 为硅基智能（GuiJi AI）的专有软件，需获得授权方可使用。

第三方组件：
- 阿里云 DashScope SDK — 按阿里云服务条款使用
- OpenCV — Apache 2.0
- DUIX SDK — 硅基智能专有
