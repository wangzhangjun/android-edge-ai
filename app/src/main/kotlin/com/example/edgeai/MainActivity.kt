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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * ============================================================================================
 * 端侧大模型推理 Demo — 聊天界面
 * ============================================================================================
 *
 * 【整体架构】
 *   用户界面 (Activity + RecyclerView 聊天列表)
 *       ↓ 启动时
 *   initializeEngine() — 加载模型到内存，创建对话会话
 *       ↓ 用户点击发送
 *   sendMessage() — 将文字/图片组装成 Contents，发送给模型
 *       ↓ 模型逐 token 生成
 *   MessageCallback.onMessage() — 流式回调，实时更新 AI 气泡
 *       ↓ 生成结束
 *   MessageCallback.onDone() — 完成
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
    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnPickImage: ImageButton
    private lateinit var ivPreview: ImageView
    private lateinit var imagePreviewContainer: FrameLayout
    private lateinit var btnRemoveImage: ImageButton

    // ======================== 聊天数据 ========================
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    // ======================== AI 核心对象 ========================
    // Engine: 推理引擎实例，加载模型权重后常驻内存，是最重量级的对象
    private var engine: Engine? = null
    // Conversation: 对话会话，内部维护对话历史（之前的问答轮次）
    private var conversation: Conversation? = null
    // 用户选择的图片（Bitmap），发送后清空
    private var selectedBitmap: Bitmap? = null

    // 模型文件名，存放在应用外部私有目录: /sdcard/Android/data/com.example.edgeai/files/
    private val modelFileName = "gemma-4-E2B-it.litertlm"

    // ======================== 图片选择器 ========================
    // registerForActivityResult: Android 现代方式注册 Activity 结果回调
    // 用户选完图片后回调，uri 是图片的内容URI
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val original = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            // 缩放到 512px 以内，防止图片过大导致模型推理失败
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
        rvChat = findViewById(R.id.rvChat)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnPickImage = findViewById(R.id.btnPickImage)
        ivPreview = findViewById(R.id.ivPreview)
        imagePreviewContainer = findViewById(R.id.imagePreviewContainer)
        btnRemoveImage = findViewById(R.id.btnRemoveImage)

        // 设置 RecyclerView
        chatAdapter = ChatAdapter(chatMessages)
        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // 新消息从底部开始显示
        }
        rvChat.adapter = chatAdapter

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
    private fun initializeEngine() {
        tvStatus.text = "状态：正在加载模型..."
        addAiMessage("模型初始化中，请稍候（首次约30-60秒）...")

        val modelPath = getExternalFilesDir(null)!!.resolve(modelFileName).absolutePath

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // -------- Step 1: 配置引擎参数 --------
                    val config = EngineConfig(
                        modelPath = modelPath,          // .litertlm 模型文件路径
                        backend = Backend.CPU(),        // 文本推理使用 CPU
                        visionBackend = Backend.CPU(),  // 图片理解也用 CPU（必须显式指定）
                        maxNumTokens = 1024,            // 单次生成最多 1024 个 token
                        cacheDir = cacheDir.absolutePath
                    )

                    // -------- Step 2: 创建引擎并加载模型权重 --------
                    engine = Engine(config)
                    engine!!.initialize() // 耗时操作：将 ~2.5GB 模型加载到内存

                    // -------- Step 3: 创建对话会话 --------
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(Content.Text("你是一个有用的AI助手，请用简洁的中文回答问题。"))
                        )
                    )
                }
                tvStatus.text = "状态：模型已就绪 ✓"
                // 替换加载提示消息
                updateLastAiMessage("模型加载成功！可输入文字或选择图片提问。")
                btnSend.isEnabled = true
                btnPickImage.isEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "模型加载失败", e)
                tvStatus.text = "状态：加载失败 ✗"
                updateLastAiMessage("模型加载失败：${e.message}\n\n请用 adb 将模型推送到：\n$modelPath")
            }
        }
    }

    // ============================================================================================
    // 【第二阶段】发送消息（文字 或 图片+文字）
    // ============================================================================================
    private fun sendMessage() {
        val input = etInput.text.toString().trim()
        if (input.isEmpty() && selectedBitmap == null) return

        val conv = conversation ?: return
        val bitmap = selectedBitmap
        val prompt = input.ifEmpty { "请描述这张图片" }

        // -------- 添加用户消息到聊天列表 --------
        chatMessages.add(ChatMessage(text = prompt, image = bitmap, isUser = true))
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        scrollToBottom()

        // 清空输入
        btnSend.isEnabled = false
        btnPickImage.isEnabled = false
        etInput.setText("")
        clearImage()

        // -------- 添加 AI 占位消息（显示"思考中..."） --------
        val aiMessage = ChatMessage(text = "", isUser = false, isLoading = true)
        chatMessages.add(aiMessage)
        val aiMessageIndex = chatMessages.size - 1
        chatAdapter.notifyItemInserted(aiMessageIndex)
        scrollToBottom()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // -------- Step 1: 构建 Contents（图片+文字 或 纯文字） --------
                    val contents = if (bitmap != null) {
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                        val imageBytes = bos.toByteArray()
                        Log.d(TAG, "图片大小: ${imageBytes.size / 1024}KB, 尺寸: ${bitmap.width}x${bitmap.height}")
                        Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(prompt)))
                    } else {
                        Contents.of(Content.Text(prompt))
                    }

                    // -------- Step 2: 流式发送并实时更新 AI 气泡 --------
                    callbackFlow {
                        val responseText = StringBuilder()
                        conv.sendMessageAsync(contents, object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val chunk = message.contents.contents
                                    .filterIsInstance<Content.Text>()
                                    .joinToString("") { it.text }
                                responseText.append(chunk)
                                trySend(responseText.toString())
                            }

                            override fun onDone() {
                                close()
                            }

                            override fun onError(throwable: Throwable) {
                                close(throwable)
                            }
                        })
                        awaitClose()
                    }.collectLatest { partialText ->
                        // 每收到新的 chunk，更新 AI 气泡内容（流式显示）
                        withContext(Dispatchers.Main) {
                            aiMessage.text = partialText
                            aiMessage.isLoading = false
                            chatAdapter.notifyItemChanged(aiMessageIndex)
                            scrollToBottom()
                        }
                    }
                }
            } catch (e: Exception) {
                aiMessage.text = "出错了：${e.message}"
                aiMessage.isLoading = false
                chatAdapter.notifyItemChanged(aiMessageIndex)
            }
            btnSend.isEnabled = true
            btnPickImage.isEnabled = true
        }
    }

    // ======================== 辅助方法 ========================

    private fun addAiMessage(text: String) {
        chatMessages.add(ChatMessage(text = text, isUser = false))
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        scrollToBottom()
    }

    private fun updateLastAiMessage(text: String) {
        val lastAi = chatMessages.lastOrNull { !it.isUser }
        if (lastAi != null) {
            lastAi.text = text
            lastAi.isLoading = false
            chatAdapter.notifyItemChanged(chatMessages.indexOf(lastAi))
        }
    }

    private fun scrollToBottom() {
        rvChat.post { rvChat.scrollToPosition(chatMessages.size - 1) }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversation?.close()
        engine?.close()
    }

    companion object {
        private const val TAG = "EdgeAI"
        private const val MAX_IMAGE_SIZE = 512
    }
}
