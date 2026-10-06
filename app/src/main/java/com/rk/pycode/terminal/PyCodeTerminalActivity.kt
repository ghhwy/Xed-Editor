package com.rk.pycode.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * PyCode 的内置终端（独立全屏 Activity）
 *
 *  · 界面：Termux 的 [TerminalView]（ANSI 颜色/光标/nano、top 这类全屏程序都能跑）
 *  · 后端：Termux 里的「终端桥」(127.0.0.1:8768) → **真** bash / python（pkg 装的东西都在）
 *  · 会话层：PyCode 改写的 socket 版（core/terminal 模块），不需要任何原生库
 *
 *  传参（可选）：
 *    cmd  —— 要跑的命令，默认 "bash -l"
 *    cwd  —— 初始目录，默认 /sdcard/PythonProjects
 *  例：adb shell am start -n com.pycode.xed.debug/com.rk.pycode.terminal.PyCodeTerminalActivity \
 *        --es cmd "bash -l" --es cwd "/sdcard/PythonProjects/code"
 */
class PyCodeTerminalActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PyCodeTerminal"
        private const val BRIDGE_HOST = "127.0.0.1"
        private const val BRIDGE_PORT = 8768

        /** 桥的启动脚本（sdcard 上那份，优先它，保证同步前也能用）。 */
        private const val BRIDGE_SCRIPT = "/sdcard/PythonProjects/_engine/tty.sh"
        private const val BASE_FONT_SIZE = 32f

        const val EXTRA_CMD = "cmd"
        const val EXTRA_CWD = "cwd"

        /** 要运行的脚本（绝对路径）；不传则运行 code/ 里最新的 .py */
        const val EXTRA_FILE = "file"

        /**
         * 「运行结束」标记文件：注入的命令末尾会写 `echo $? > 这个文件`，
         * App 轮询它的修改时间来判断脚本跑完了没有（不需要改协议）。
         */
        private const val RC_FILE = "/sdcard/PythonProjects/_engine/_last_rc"
    }

    private lateinit var terminalView: TerminalView
    private lateinit var statusText: TextView
    private lateinit var runButton: TextView
    private lateinit var ctrlButton: TextView
    private lateinit var altButton: TextView
    private lateinit var client: PyCodeTerminalClient
    private var session: TerminalSession? = null

    /** Ctrl / Alt 是否处于"按住"状态（给 [PyCodeTerminalClient.readControlKey] 用）。 */
    var ctrlPressed = false
        private set
    var altPressed = false
        private set

    private var scaleFactor = 1.0f

    /** 当前字号（dp）——TerminalRenderer.mTextSize 是包内可见，读不了，所以自己记一份。 */
    private var currentFontSize = BASE_FONT_SIZE.toInt()

    /** 当前是否"正在跑脚本"：决定按钮显示 ▶ 还是 ■。 */
    private var running = false

    /** 要运行哪个脚本（绝对路径）；null = 运行 code/ 里最新的 .py。 */
    private var runTarget: String? = null

    /** 初始工作目录（也是没指定文件时 r.sh 的工作区）。 */
    private var workDir: String = "/sdcard/PythonProjects/code"

    private val rcHandler = Handler(Looper.getMainLooper())
    private var rcWatchStart = 0L
    private val rcPoll = object : Runnable {
        override fun run() {
            if (!running) return
            val f = File(RC_FILE)
            if (f.exists() && f.lastModified() >= rcWatchStart) {
                val rc = try {
                    f.readText().trim()
                } catch (t: Throwable) {
                    "?"
                }
                setRunning(false)
                statusText.text = "运行结束（退出码 $rc）"
                return
            }
            rcHandler.postDelayed(this, 600)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()


        client = PyCodeTerminalClient(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // ---------------- 顶部小工具条 ----------------
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1B1B.toInt())
            setPadding(dp(6), 0, dp(6), 0)
        }
        runButton = smallButton("▶") { toggleRun() }
        runButton.setTextColor(0xFF9BE37F.toInt())
        bar.addView(runButton)
        bar.addView(smallButton("✕") { finish() })
        statusText = TextView(this).apply {
            text = "正在连接终端桥…"
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(smallButton("⌨") { toggleKeyboard() })
        bar.addView(smallButton("A-") { changeFontSize(-2) })
        bar.addView(smallButton("A+") { changeFontSize(+2) })
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )

        // ---------------- 终端本体 ----------------
        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(client)
            setTextSize(BASE_FONT_SIZE.toInt())
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(
            terminalView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // ---------------- 底部扩展键 ----------------
        root.addView(
            buildExtraKeys(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        )

        setContentView(root)

        // 顶栏要避开系统状态栏：否则顶栏那几个按钮会被状态栏的触摸区域吃掉，点不动
        root.fitsSystemWindows = true
        window.statusBarColor = 0xFF1B1B1B.toInt()
        window.navigationBarColor = 0xFF141414.toInt()

        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: "bash -l"
        val cwd = intent?.getStringExtra(EXTRA_CWD) ?: "/sdcard/PythonProjects/code"
        workDir = cwd
        runTarget = intent?.getStringExtra(EXTRA_FILE)
        ensureBridgeThenStart(cmd, cwd)
    }

    // ------------------------------------------------------------------ 连桥

    private fun bridgeAlive(): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(BRIDGE_HOST, BRIDGE_PORT), 400)
            true
        }
    } catch (t: Throwable) {
        false
    }

    /** 先确认桥在跑；不在就请 Termux 拉起来（最多重试 2 次），然后再建会话。 */
    private fun ensureBridgeThenStart(cmd: String, cwd: String) {
        thread(name = "ensureBridge") {
            var alive = bridgeAlive()
            var tries = 0
            while (!alive && tries < 2) {
                tries++
                requestBridgeStart()
                Thread.sleep(1500)
                alive = bridgeAlive()
            }
            val ok = alive
            runOnUiThread {
                if (!ok) {
                    statusText.text = "终端桥没起来（Termux 是不是被杀了？）"
                }
                startSession(cmd, cwd)
            }
        }
    }

    /** 用 RUN_COMMAND 请 Termux 执行 `sh tty.sh start`（只有本 App 有这个权限）。 */
    private fun requestBridgeStart() {
        try {
            val i = Intent()
            i.setClassName("com.termux", "com.termux.app.RunCommandService")
            i.action = "com.termux.RUN_COMMAND"
            i.putExtra(
                "com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/usr/bin/sh"
            )
            i.putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf(
                    "-c",
                    "sh $BRIDGE_SCRIPT start >> /sdcard/PythonProjects/_engine/tty_auto.log 2>&1"
                )
            )
            i.putExtra(
                "com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home"
            )
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            startForegroundService(i)
            Log.i(TAG, "已请求 Termux 启动终端桥")
        } catch (t: Throwable) {
            Log.e(TAG, "请求启动终端桥失败", t)
        }
    }

    private fun startSession(cmd: String, cwd: String) {
        val extra = "\"cmd\":\"" + escapeJson(cmd) + "\",\"cwd\":\"" + escapeJson(cwd) + "\""
        val s = TerminalSession(BRIDGE_HOST, BRIDGE_PORT, extra, 10000, client)
        session = s
        terminalView.attachSession(s)
        terminalView.requestFocus()
        statusText.text = cwd
        // 注意：这里**不要**自动弹键盘 —— 从编辑器 ▶ 进来时用户多半是在看输出

        // 从编辑器「▶ 运行」进来时会带 file：等 shell 起来后自动跑一次
        if (runTarget != null) {
            terminalView.postDelayed({ doRun() }, 900)
        }
    }

    // ------------------------------------------------------------------ 运行 / 中断

    /** 一个按钮切两态：▶ 运行 / ■ 中断。 */
    private fun toggleRun() {
        if (running) doInterrupt() else doRun()
    }

    private fun setRunning(value: Boolean) {
        running = value
        runButton.text = if (value) "■" else "▶"
        runButton.setTextColor(
            if (value) 0xFFFF8A65.toInt() else 0xFF9BE37F.toInt()
        )
    }

    /** 单引号包住路径（处理路径里的空格/中文）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 运行：把命令写进终端（跟用户手打一样），末尾写一个退出码标记供轮询。 */
    private fun doRun() {
        val s = session ?: return
        val target = runTarget
        val cmd = if (target != null) {
            val dir = File(target).parent ?: workDir
            "cd " + shellQuote(dir) + " && python " + shellQuote(target)
        } else {
            "cd " + shellQuote(workDir) + " && sh ~/r.sh"
        } + " ; echo $? > " + RC_FILE

        try {
            File(RC_FILE).delete()
        } catch (t: Throwable) {
        }
        rcWatchStart = System.currentTimeMillis()
        val bytes = (cmd + "\r").toByteArray()
        s.write(bytes, 0, bytes.size)
        setRunning(true)
        statusText.text = if (target != null) "运行中：" + File(target).name else "运行中：最新脚本"
        rcHandler.removeCallbacks(rcPoll)
        rcHandler.postDelayed(rcPoll, 900)
    }

    /** 中断：Ctrl+C（跟终端里按 Ctrl+C 一样）。 */
    private fun doInterrupt() {
        val s = session ?: return
        // 先照常发一个 Ctrl+C（大多数程序吃这个）
        s.write(byteArrayOf(3), 0, 1)
        // 再由桥兜底：直接杀掉当前前台进程组（有些程序/状态下 ^C 不产生 SIGINT）
        s.killForeground()
        rcHandler.removeCallbacks(rcPoll)
        setRunning(false)
        statusText.text = "已中断"
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ------------------------------------------------------------------ 回调

    fun onTerminalOutput() {
        terminalView.onScreenUpdated()
    }

    fun onTerminalTitle(title: String?) {
        if (!title.isNullOrEmpty()) statusText.text = title
    }

    fun onTerminalSessionFinished() {
        statusText.text = "会话已结束 —— 可点 ✕ 关闭"
    }

    fun onEmulatorSet() {
        // ⚠️ 这里**绝不能**自动弹键盘！
        // 收起键盘 → 视图尺寸变化 → TerminalView.updateSize() → onEmulatorSet() → 再弹 →
        // 用户就会看到"收起后一秒又弹出来"的死循环。
        // 需要键盘时，用户点一下终端区域或点顶栏 ⌨ 即可（见 onSingleTapUp / toggleKeyboard）。
    }

    fun onScale(scale: Float): Float {
        val clamped = scale.coerceIn(0.35f, 2.5f)
        scaleFactor = clamped
        currentFontSize = (BASE_FONT_SIZE * clamped).toInt().coerceIn(12, 72)
        terminalView.post { terminalView.setTextSize(currentFontSize) }
        return clamped
    }

    fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView.postDelayed({ imm.showSoftInput(terminalView, 0) }, 200)
    }

    /** 输入法此刻是否真的显示着（不能只看 imm.isActive —— 键盘收起时它也可能返回 true）。 */
    private fun isImeVisible(): Boolean = try {
        // 注意：getRootWindowInsets() 返回的已经是 WindowInsetsCompat，不要再套 toWindowInsetsCompat()
        ViewCompat.getRootWindowInsets(terminalView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
    } catch (t: Throwable) {
        false
    }

    /** 显示/收起输入法（顶栏 ⌨ 按钮）。 */
    fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView.postDelayed(
            {
                if (isImeVisible()) {
                    imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
                } else {
                    imm.showSoftInput(terminalView, 0)
                }
            },
            120
        )
    }

    fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    fun pasteInto(target: TerminalSession) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isEmpty()) return
        val bytes = text.toByteArray()
        target.write(bytes, 0, bytes.size)
    }

    // ------------------------------------------------------------------ UI 小工具

    private fun changeFontSize(delta: Int) {
        currentFontSize = (currentFontSize + delta).coerceIn(12, 72)
        terminalView.setTextSize(currentFontSize)
        statusText.text = "字号 $currentFontSize"
    }

    private fun buildExtraKeys(): View {
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xFF141414.toInt())
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun add(label: String, action: () -> Unit) {
            row.addView(keyButton(label, action))
        }

        add("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        add("TAB") { sendKey(KeyEvent.KEYCODE_TAB) }
        ctrlButton = keyButton("CTRL") {
            ctrlPressed = !ctrlPressed
            altPressed = false
            refreshModifierButtons()
        }
        row.addView(ctrlButton)
        altButton = keyButton("ALT") {
            altPressed = !altPressed
            ctrlPressed = false
            refreshModifierButtons()
        }
        row.addView(altButton)
        add("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        add("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        add("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        add("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        add("PgUp") { sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        add("PgDn") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        add("^C") { sendText("\u0003") }
        add("|") { sendText("|") }
        add("-") { sendText("-") }
        add("/") { sendText("/") }
        add("~") { sendText("~") }

        scroll.addView(row)
        return scroll
    }

    private fun refreshModifierButtons() {
        ctrlButton.setTextColor(if (ctrlPressed) 0xFF66D9EF.toInt() else 0xFFCCCCCC.toInt())
        altButton.setTextColor(if (altPressed) 0xFF66D9EF.toInt() else 0xFFCCCCCC.toInt())
    }

    /** 把扩展键合成 KeyEvent 丢给终端（Ctrl/Alt 由 client.readControlKey()/readAltKey() 提供）。 */
    private fun sendKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        var meta = 0
        if (ctrlPressed) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altPressed) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        terminalView.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        terminalView.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** 直接送一串文本（给 | - / ~ ^C 这类用）。 */
    private fun sendText(text: String) {
        val s = session ?: return
        val bytes = text.toByteArray()
        s.write(bytes, 0, bytes.size)
    }

    private fun keyButton(label: String, action: () -> Unit): TextView =
        smallButton(label, action).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

    private fun smallButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener { action() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ 生命周期

    override fun onResume() {
        super.onResume()
        terminalView.post { terminalView.onScreenUpdated() }
    }

    override fun onDestroy() {
        try {
            rcHandler.removeCallbacks(rcPoll)
        } catch (t: Throwable) {
        }
        // 关闭界面 = 结束远端 shell（桥会把对应 pty 收掉）
        try {
            session?.finishIfRunning()
        } catch (t: Throwable) {
            Log.e(TAG, "收尾会话失败", t)
        }
        session = null
        super.onDestroy()
    }
}
