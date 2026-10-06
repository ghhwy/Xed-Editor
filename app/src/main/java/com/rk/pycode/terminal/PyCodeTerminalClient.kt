package com.rk.pycode.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * PyCode 内置终端的两套回调（Termux 那边是分开的两个类，这里合成一个）：
 *
 *  · [TerminalViewClient]    —— 终端 View 要问的：Ctrl/Alt 有没有按下、点一下要不要弹键盘、缩放等
 *  · [TerminalSessionClient] —— 会话要告诉我们的：屏幕变了、标题变了、会话结束、复制粘贴/响铃等
 */
class PyCodeTerminalClient(private val activity: PyCodeTerminalActivity) :
    TerminalViewClient, TerminalSessionClient {

    // ------------------------------------------------------------------ View 侧

    override fun onScale(scale: Float): Float = activity.onScale(scale)

    override fun onSingleTapUp(e: MotionEvent) {
        // 点一下终端 → 弹输入法（Termux 的习惯）
        activity.showKeyboard()
    }

    /** 返回键当成 ESC 送进终端（退出用顶部 ✕）。 */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        // 不需要额外处理
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = activity.ctrlPressed

    override fun readAltKey(): Boolean = activity.altPressed

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean =
        false

    override fun onEmulatorSet() {
        // 模拟器就绪：交给 Activity 收尾（比如收键盘）
        activity.onEmulatorSet()
    }

    // ------------------------------------------------------------------ Session 侧

    override fun onTextChanged(changedSession: TerminalSession) {
        // 有输出 → 让终端 View 重绘并滚动
        activity.onTerminalOutput()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        activity.onTerminalTitle(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        activity.onTerminalSessionFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        activity.copyToClipboard(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        session?.let { activity.pasteInto(it) }
    }

    override fun onBell(session: TerminalSession) {
        // 忽略响铃
    }

    override fun onColorsChanged(session: TerminalSession) {
        // 用默认配色
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // 光标闪烁由 View 自己管
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        // socket 模式没有真实 pid
    }

    override fun getTerminalCursorStyle(): Int? = null

    // ------------------------------------------------------------------ 日志

    override fun logError(tag: String, message: String) = Log.e(tag, message)

    override fun logWarn(tag: String, message: String) = Log.w(tag, message)

    override fun logInfo(tag: String, message: String) = Log.i(tag, message)

    override fun logDebug(tag: String, message: String) = Log.d(tag, message)

    override fun logVerbose(tag: String, message: String) = Log.v(tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        Log.e(tag, message, e)

    override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "stacktrace", e)
}
