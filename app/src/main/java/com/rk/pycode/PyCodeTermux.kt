package com.rk.pycode

import android.content.Intent
import android.view.KeyEvent
import com.rk.commands.ActionContext
import com.rk.commands.EditorActionContext
import com.rk.commands.GlobalCommand
import com.rk.commands.KeyCombination
import com.rk.icons.Icon
import com.rk.resources.drawables

/**
 * PyCode 补丁：这是「终端」入口 —— 但它不再打开自带的 PRoot 终端，
 * 而是直接跳转到 Termux 应用（真正的 Linux 环境在那里）。
 */
object TermuxTerminalCommand : GlobalCommand() {
    override val id: String = "pycode.terminal.termux"

    override fun getLabel(): String = "终端"

    override fun execute(context: ActionContext) {
        // PyCode 补丁：打开**内置终端**（全屏 Activity）——它连的是 Termux 里的「终端桥」，
        // 所以界面在 PyCode 里、环境却是真 Termux；不再跳 Termux 应用。
        // 如果是从编辑器里触发的，把当前文件也带过去（终端的 ▶ 按钮就能直接跑它）。
        try {
            val i = Intent()
            i.setClassName(context.currentActivity, "com.rk.pycode.terminal.PyCodeTerminalActivity")
            var cwd = "/sdcard/PythonProjects/code"
            val file = (context as? EditorActionContext)?.editorTab?.file?.getAbsolutePath()
            if (file != null) {
                i.putExtra("file", file)
                java.io.File(file).parent?.let { cwd = it }
            }
            i.putExtra("cwd", cwd)
            context.currentActivity.startActivity(i)
        } catch (t: Throwable) {
            // 兜底：内置终端起不来就直接开 Termux
            try {
                val i = Intent()
                i.setClassName("com.termux", "com.termux.app.TermuxActivity")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.currentActivity.startActivity(i)
            } catch (_: Throwable) {
            }
        }
    }

    override fun isSupported(): Boolean = true

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.terminal)

    override val defaultKeybinds: KeyCombination =
        KeyCombination(keyCode = KeyEvent.KEYCODE_J, ctrl = true)
}
