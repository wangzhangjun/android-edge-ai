package com.example.edgeai

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * ============================================================================================
 * 端侧大模型推理 Demo — 主要流程概览
 * ============================================================================================
 *
 * 【整体架构】
 *   用户界面 (Activity)
 *       ↓ 启动时
 *   initializeEngine() — 加载模型到内存，创建对话会话
 *       ↓ 用户点击发送
 *   sendMessage() — 将文字/图片组装成 Contents，发送给模型
 *       ↓ 模型逐 token 生成
 *   MessageCallback.onMessage() — 流式回调，逐步拼接结果
 *       ↓ 生成结束
 *   MessageCallback.onDone() — 完成，UI 显示完整回复
 *
 * 【关键概念】
 *   - Engine：推理引擎，持有模型权重（约2.5GB），整个生命周期只创建一次
 *   - Conversation：对话会话，维护多轮对话历史，可复用
 *   - Contents：一次消息的内容，可包含文字(Content.Text)和图片(Content.ImageBytes)
 *   - MessageCallback：异步回调接口，模型每生成一段文字就调用 onMessage
 *
 * ============================================================================================
 */
class MainActivity : AppCompatActivity() {

    // ======================== UI 控件 ========================
    private lateinit var tvStatus: TextView
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnPickImage: ImageButton
    private lateinit var ivPreview: ImageView
    private lateinit var imagePreviewContainer: FrameLayout
    private lateinit var btnRemoveImage: ImageButton

    // ======================== AI 核心对象 ========================
    // Engine: 推理引擎实例，加载模型权重后常驻内存，是最重量级的对象
    private var engine: Engine? = null
    // Conversation: 对话会话，内部维护对话历史（之前的问答轮次）
    // 每次 sendMessage 都会把历史 + 新消息一起送入模型
    private var conversation: Conversation? = null
    // 用户选择的图片（Bitmap），发送后清空
    private var selectedBitmap: Bitmap? = null

    // 模型文件名，存放在应用外部私有目录: /sdcard/Android/data/com.example.edgeai/files/
    private val modelFileName = "gemma-4-E2B-it.litertlm"

    // ======================== 图片选择器 ========================
    // registerForActivityResult: Android 现代方式注册 Activity 结果回调
    // ActivityResultContracts.GetContent(): 打开系统文件选择器
    // 用户选完图片后回调这个 lambda，uri 是图片的内容URI
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            // 通过 ImageDecoder 将 URI 解码为 Bitmap
            val source = ImageDecoder.createSource(contentResolver, uri)
            val original = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                // ALLOCATOR_SOFTWARE: 强制使用软件内存（非硬件缓冲区），确保后续能读取像素数据
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            // 缩放到 512px 以内，防止图片过大导致模型推理失败或OOM
            selectedBitmap = resizeBitmap(original, MAX_IMAGE_SIZE)
            ivPreview.setImageBitmap(selectedBitmap)
            imagePreviewContainer.visibility = View.VISIBLE
        }
    }

    // 等比缩放图片，最长边不超过 maxSize 像素
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvOutput = findViewById(R.id.tvOutput)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnPickImage = findViewById(R.id.btnPickImage)
        ivPreview = findViewById(R.id.ivPreview)
        imagePreviewContainer = findViewById(R.id.imagePreviewContainer)
        btnRemoveImage = findViewById(R.id.btnRemoveImage)

        btnSend.setOnClickListener { sendMessage() }
        btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        btnRemoveImage.setOnClickListener { clearImage() }

        // Activity 创建时立即开始加载模型
        initializeEngine()
    }

    private fun clearImage() {
        selectedBitmap = null
        ivPreview.setImageBitmap(null)
        imagePreviewContainer.visibility = View.GONE
    }

    // ============================================================================================
    // 【第一阶段】模型加载
    // ============================================================================================
    // 流程: 读取模型文件 → 创建 Engine → initialize() 加载权重到内存 → 创建 Conversation
    // 耗时: 首次约 30-60 秒（2.5GB 模型加载到 RAM）
    // 线程: 在 Dispatchers.IO 后台线程执行，不阻塞 UI
    // ============================================================================================
    private fun initializeEngine() {
        tvStatus.text = "状态：正在加载模型..."
        tvOutput.text = "模型初始化中，请稍候（首次约30-60秒）..."

        // 模型文件的完整路径: /sdcard/Android/data/com.example.edgeai/files/gemma-4-E2B-it.litertlm
        val modelPath = getExternalFilesDir(null)!!.resolve(modelFileName).absolutePath

        // lifecycleScope: 绑定 Activity 生命周期的协程作用域
        // Activity 销毁时协程自动取消，防止泄漏
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // -------- Step 1: 配置引擎参数 --------
                    val config = EngineConfig(
                        modelPath = modelPath,          // .litertlm 模型文件路径
                        backend = Backend.CPU(),        // 文本推理使用 CPU（Pixel6/一加不支持 OpenCL GPU）
                        visionBackend = Backend.CPU(),  // 图片理解也用 CPU（Gemma4 多模态必须显式指定，否则 native crash）
                        maxNumTokens = 1024,            // 单次生成最多 1024 个 token
                        cacheDir = cacheDir.absolutePath // KV-cache 等临时文件的存放目录
                    )

                    // -------- Step 2: 创建引擎并加载模型 --------
                    // Engine 构造: 仅创建引擎对象，还未加载模型
                    engine = Engine(config)
                    // initialize(): 真正执行模型加载 —— 将 2.5GB 权重从文件读入内存
                    // 这是最耗时的步骤，完成后模型即可进行推理
                    engine!!.initialize()

                    // -------- Step 3: 创建对话会话 --------
                    // Conversation 是轻量级对象，内部维护对话历史
                    // systemInstruction: 系统提示词，定义 AI 角色和行为规则
                    // 后续每次 sendMessage 都会自动带上系统提示词 + 历史对话
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(Content.Text("你是一个有用的AI助手，请用简洁的中文回答问题。"))
                        )
                    )
                }
                // 回到主线程更新 UI
                tvStatus.text = "状态：模型已就绪 ✓"
                tvOutput.text = "模型加载成功！可输入文字或选择图片提问。"
                btnSend.isEnabled = true
                btnPickImage.isEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "模型加载失败", e)
                tvStatus.text = "状态：加载失败 ✗"
                tvOutput.text = "模型加载失败：${e.message}\n\n请用 adb 将模型推送到：\n$modelPath"
            }
        }
    }

    // ============================================================================================
    // 【第二阶段】发送消息（文字 或 图片+文字）
    // ============================================================================================
    // 流程:
    //   1. 收集用户输入（文字 + 可选图片）
    //   2. 组装 Contents 对象（多模态内容容器）
    //   3. 调用 conversation.sendMessageAsync() 发送给模型
    //   4. 通过 MessageCallback 流式接收模型生成的文本
    //   5. 生成完毕后显示完整回复
    // ============================================================================================
    private fun sendMessage() {
        val input = etInput.text.toString().trim()
        // 文字和图片至少要有一个
        if (input.isEmpty() && selectedBitmap == null) return

        val conv = conversation ?: return
        val bitmap = selectedBitmap
        // 如果只有图片没有文字，使用默认提示词
        val prompt = input.ifEmpty { "请描述这张图片" }

        // 禁用按钮防止重复发送
        btnSend.isEnabled = false
        btnPickImage.isEnabled = false
        etInput.setText("")
        tvOutput.text = "思考中..."

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {

                    // -------- Step 1: 构建 Contents（消息内容） --------
                    // Contents 是 LiteRT-LM 的消息容器，可包含多种内容类型：
                    //   - Content.Text("文字")     → 文本内容
                    //   - Content.ImageBytes(bytes) → 图片内容（JPEG/PNG 字节数组）
                    // 多模态时将图片和文字放在同一个 list 中
                    val contents = if (bitmap != null) {
                        // 有图片：先将 Bitmap 压缩为 JPEG 字节数组
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                        val imageBytes = bos.toByteArray()
                        Log.d(TAG, "图片大小: ${imageBytes.size / 1024}KB, 尺寸: ${bitmap.width}x${bitmap.height}")
                        // 组合图片 + 文字，模型会"看到"图片并结合文字回答
                        Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(prompt)))
                    } else {
                        // 纯文字
                        Contents.of(Content.Text(prompt))
                    }

                    // -------- Step 2: 发送给模型并流式接收回复 --------
                    // callbackFlow: 将回调式 API 转换为 Kotlin Flow（响应式流）
                    // 这样可以用协程的方式处理异步回调
                    callbackFlow {
                        val responseText = StringBuilder()

                        // sendMessageAsync: 异步发送消息给模型
                        // 内部流程:
                        //   1. 将 systemInstruction + 历史对话 + 本次 contents 拼接为完整 prompt
                        //   2. Tokenize → Prefill（处理输入）→ Decode（逐 token 生成）
                        //   3. 每生成若干 token 就回调一次 onMessage
                        conv.sendMessageAsync(contents, object : MessageCallback {

                            // onMessage: 模型每生成一段文字就回调
                            // message 中包含新生成的文本片段（chunk）
                            override fun onMessage(message: Message) {
                                // 从 Message 中提取文本内容
                                // message.contents.contents 是一个 Content 列表
                                // 过滤出 Content.Text 类型，拼接文本
                                val chunk = message.contents.contents
                                    .filterIsInstance<Content.Text>()
                                    .joinToString("") { it.text }
                                // 累加到完整回复
                                responseText.append(chunk)
                                // 通过 Flow 发送当前累积的文本（可用于实时更新 UI）
                                trySend(responseText.toString())
                            }

                            // onDone: 模型生成结束（遇到结束符或达到 maxNumTokens）
                            override fun onDone() {
                                close() // 关闭 Flow，表示数据流结束
                            }

                            // onError: 推理过程中出错
                            override fun onError(throwable: Throwable) {
                                close(throwable) // 关闭 Flow 并传递异常
                            }
                        })

                        // awaitClose: 挂起协程，等待 Flow 被 close()
                        // 直到 onDone 或 onError 调用 close() 才会继续
                        awaitClose()
                    }.last() // .last(): 取 Flow 中最后一个值，即模型的完整回复
                }

                // -------- Step 3: 回到主线程，显示结果 --------
                tvOutput.text = response
            } catch (e: Exception) {
                tvOutput.text = "出错了：${e.message}"
            }
            // 清理图片并恢复按钮状态
            clearImage()
            btnSend.isEnabled = true
            btnPickImage.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放资源：关闭对话会话和引擎，释放模型占用的内存
        conversation?.close()
        engine?.close()
    }

    companion object {
        private const val TAG = "EdgeAI"
        private const val MAX_IMAGE_SIZE = 512 // 图片最长边限制（像素）
    }
}
