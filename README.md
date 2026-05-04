# Android Edge AI Demo

一个极简的 Android 端侧大模型推理 Demo，使用 [LiteRT-LM](https://github.com/google-ai-edge/litert-lm) 在手机本地运行 Gemma 4 模型，支持文字对话和图片多模态问答，完全离线，无需联网。

## 功能

- 纯端侧推理，模型运行在手机本地，数据不出设备
- 文字对话：输入问题，AI 实时流式回复
- 图片+文字多模态：选择图片并提问，AI 能"看懂"图片内容
- 多轮对话：自动维护上下文历史

## 环境要求

| 项目 | 要求 |
|------|------|
| Android 版本 | 8.0+ (API 26+) |
| 运行内存 | 至少 6GB（推荐 8GB+） |
| 存储空间 | 约 2.5GB（存放模型文件） |
| 开发环境 | Android Studio、JDK 17+、Gradle 8.13 |

## 快速开始

### 1. 下载模型

wget -O gemma-4-E2B-it.litertlm "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"

> 如果设备内存 12GB+，也可选择 E4B 变体（~5GB），推理效果更好但速度更慢。

### 2. 推送模型到手机

```bash
# 确认手机已通过 USB 连接并开启 USB 调试
adb devices

# 安装 APK（先编译，或使用 Release 页面的预编译 APK）
adb install app-debug.apk

# 创建应用私有目录并推送模型（无需额外存储权限）
adb shell mkdir -p /sdcard/Android/data/com.example.edgeai/files/
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.example.edgeai/files/
adb shell chmod 644 /sdcard/Android/data/com.example.edgeai/files/gemma-4-E2B-it.litertlm
```

> **注意**：模型必须放在应用私有目录 `/sdcard/Android/data/com.example.edgeai/files/` 下。Android 11+ 的 Scoped Storage 限制，app 无法直接读取 `/sdcard/Download/` 等公共目录。

### 3. 编译运行

```bash
# 克隆项目
git clone https://github.com/YOUR_USERNAME/android-edge-ai.git
cd android-edge-ai

# 编译 Debug APK
./gradlew assembleDebug

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk

# 安装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动
adb shell am start -n com.example.edgeai/.MainActivity
```

### 4. 使用

1. 首次启动需等待 30-60 秒加载模型（状态栏会显示进度）
2. 模型就绪后，输入框输入问题点击"发送"即可对话
3. 点击左侧图片按钮可选择相册图片，配合文字进行多模态问答
4. 如果只选了图片没输入文字，默认会用"请描述这张图片"作为提示词

## 项目结构

```
android-edge-ai/
├── build.gradle.kts                    # 根级构建配置 (AGP 8.13.2, Kotlin 2.2.21)
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts                # 应用构建配置 (litertlm-android:0.10.0)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/example/edgeai/
│       │   └── MainActivity.kt         # 核心代码（模型加载 + 推理 + UI）
│       └── res/layout/
│           └── activity_main.xml       # 界面布局
└── models/                             # 模型文件存放（已 gitignore）
```

## 核心 API 流程

```kotlin
// 1. 配置并初始化引擎（加载模型权重到内存）
val config = EngineConfig(
    modelPath = "/path/to/gemma-4-E2B-it.litertlm",
    backend = Backend.CPU(),
    visionBackend = Backend.CPU(),  // 多模态图片必须指定
    maxNumTokens = 1024,
    cacheDir = cacheDir.absolutePath
)
val engine = Engine(config)
engine.initialize()  // 耗时操作，约30-60秒

// 2. 创建对话（设定系统提示词）
val conversation = engine.createConversation(
    ConversationConfig(
        systemInstruction = Contents.of(Content.Text("你是一个AI助手"))
    )
)

// 3. 发送消息（纯文字）
conversation.sendMessageAsync(
    Contents.of(Content.Text("你好")),
    object : MessageCallback {
        override fun onMessage(message: Message) { /* 流式接收文本 */ }
        override fun onDone() { /* 生成完毕 */ }
        override fun onError(throwable: Throwable) { /* 出错 */ }
    }
)

// 4. 发送多模态消息（图片 + 文字）
val imageBytes = bitmap.toJpegBytes()
conversation.sendMessageAsync(
    Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text("这是什么？"))),
    callback
)
```

## 常见问题

**Q: 模型加载失败 "Permission denied"**

A: 确保模型文件在应用私有目录且权限正确：
```bash
adb shell chmod 644 /sdcard/Android/data/com.example.edgeai/files/gemma-4-E2B-it.litertlm
```

**Q: 能否使用 GPU 加速？**

A: `Backend.GPU()` 依赖 OpenCL 库。部分手机（如一加等）系统未暴露 OpenCL，只能用 CPU。Pixel 9+ 等设备可尝试 GPU 后端。

**Q: 发送图片后闪退 (SIGSEGV)**

A: `EngineConfig` 必须配置 `visionBackend = Backend.CPU()`。Gemma 4 处理多模态内容时，未指定 visionBackend 会导致 native crash。

**Q: 支持哪些手机？**

A: 理论上所有 Android 8.0+、6GB+ RAM 的手机都可以运行。已测试：
- Pixel 6 (8GB RAM, Tensor G1)
- OnePlus PJF110 (16GB RAM, Snapdragon 7+ Gen3)

## 技术栈

- Kotlin + Android View (非 Compose，保持极简)
- [LiteRT-LM](https://github.com/google-ai-edge/litert-lm) 0.10.0 — Google 端侧 LLM 推理框架
- Gemma 4 E2B (2.3B effective params) — Google 开源多模态模型
- Kotlin Coroutines + Flow — 异步推理和流式响应

## License

MIT
