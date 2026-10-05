package com.rk.runner.runners

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.rk.activities.main.MainActivity
import com.rk.file.FileObject
import com.rk.file.toFileWrapper
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.runner.FileRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PyCode 补丁：把「运行」按钮改成在 Termux 里执行，并且**运行结果直接在本 App 内打开**。
 *
 *  1) 通过 RUN_COMMAND 让 Termux 用真实 Python 跑这个文件
 *  2) 输出写到项目目录 _run_output.txt
 *  3) 等 2 秒后，自动把这个输出文件在 App 里打开（不用切到 Termux）
 */
object TermuxRunner : FileRunner() {
    override val id: String = "termux_runner"
    override val label: String = "在 Termux 里运行"
    override val description: String = "用 Termux 中的 Python 执行，输出直接显示在 App 里"

    private const val RUN_LOG = "/sdcard/PythonProjects/_run_output.txt"

    override fun getIcon(context: Context): Icon = Icon.ResourceIcon(drawables.run)

    override fun matcher(fileObject: FileObject): Boolean =
        fileObject.getName().endsWith(".py")

    override suspend fun run(activity: Activity, fileObject: FileObject) {
        val path = fileObject.getAbsolutePath()
        val dir = File(path).parent ?: "/sdcard/PythonProjects"
        val cmd = "cd " + dir + " && python " + path + " > " + RUN_LOG + " 2>&1"
        Log.i("PyCode", "TermuxRunner: " + cmd)

        try {
            File(RUN_LOG).parentFile?.mkdirs()
            File(RUN_LOG).writeText("(正在 Termux 里运行…)\n")
        } catch (t: Throwable) {
            Log.e("PyCode", "写运行日志失败", t)
        }

        var sent = false
        try {
            val i = Intent()
            i.setClassName("com.termux", "com.termux.app.RunCommandService")
            i.action = "com.termux.RUN_COMMAND"
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            activity.startForegroundService(i)
            sent = true
        } catch (t: Throwable) {
            Log.e("PyCode", "RUN_COMMAND 失败", t)
        }

        if (!sent) {
            Toast.makeText(activity, "无法调用 Termux 服务，已改为打开 Termux", Toast.LENGTH_LONG).show()
            try {
                val open = Intent()
                open.setClassName("com.termux", "com.termux.app.TermuxActivity")
                activity.startActivity(open)
            } catch (_: Throwable) {
            }
            return
        }

        // PyCode 补丁（第 14 轮）：等输出写完，再让 App 里那份运行输出**从磁盘重新读**。
        // 之前只调 openFile(...)：如果该文件已经是打开的标签页，它只会切过去、不重读盘，
        // 于是编辑器一直显示旧快照（现象：文件在 MT 管理器里变了，App 里没变）。
        try {
            val logFile = File(RUN_LOG)

            // 1) 等文件"写稳"：大小 + mtime 连续约 1.2 秒不再变化（最多等 20 秒）
            var waited = 0L
            var lastSize = -1L
            var lastMtime = -1L
            var lastChangeAt = 0L
            while (waited < 20000L) {
                delay(400)
                waited += 400
                val exists = logFile.exists()
                val size = if (exists) logFile.length() else -1L
                val mtime = if (exists) logFile.lastModified() else -1L
                if (size != lastSize || mtime != lastMtime) {
                    lastSize = size
                    lastMtime = mtime
                    lastChangeAt = waited
                } else if (lastChangeAt > 0L && waited - lastChangeAt >= 1200L) {
                    break
                }
            }

            if (!logFile.exists()) {
                Toast.makeText(activity, "运行输出见 _run_output.txt", Toast.LENGTH_LONG).show()
                return
            }

            // 2) 已在标签页里 → 刷新它；没有 → 按老逻辑打开
            val main = MainActivity.instance
            val openedTab =
                main?.viewModel?.editorManager?.tabs
                    ?.firstOrNull { it.file?.getAbsolutePath() == logFile.absolutePath }

            if (openedTab != null) {
                openedTab.refresh()
                withContext(Dispatchers.Main) {
                    val index = main.viewModel?.tabs?.indexOf(openedTab) ?: -1
                    if (index >= 0) main.viewModel?.tabManager?.setCurrentTab(index)
                }
                Log.i("PyCode", "已刷新已打开的运行输出标签页")
            } else {
                main?.viewModel?.editorManager?.openFile(
                    logFile.toFileWrapper(),
                    projectRoot = null,
                    switchToTab = true,
                )
                Log.i("PyCode", "已在 App 内打开运行输出")
            }
        } catch (t: Throwable) {
            Log.e("PyCode", "刷新/打开运行输出失败", t)
            Toast.makeText(activity, "运行输出见 _run_output.txt", Toast.LENGTH_LONG).show()
        }
    }
}
