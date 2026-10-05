package com.rk.runner.runners

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.rk.file.FileObject
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.runner.FileRunner
import java.io.File

/**
 * PyCode 补丁：把「运行」按钮改成在 Termux 里执行。
 *
 * 不再使用 Xed 自带的 PRoot 沙箱（在部分机型上不可用），
 * 而是通过 Termux 的 RUN_COMMAND 接口，用你 Termux 里真实的 Python 跑代码，
 * 输出写到项目目录下的 _run_output.txt（就在你的文件树里，随时点开看）。
 */
object TermuxRunner : FileRunner() {
    override val id: String = "termux_runner"
    override val label: String = "在 Termux 里运行"
    override val description: String = "用 Termux 中的 Python 执行（推荐）"

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

        if (sent) {
            Toast.makeText(
                activity,
                "已在 Termux 里运行，输出见 PythonProjects/_run_output.txt",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                activity,
                "无法调用 Termux 服务，已改为打开 Termux",
                Toast.LENGTH_LONG
            ).show()
            try {
                val open = Intent()
                open.setClassName("com.termux", "com.termux.app.TermuxActivity")
                activity.startActivity(open)
            } catch (_: Throwable) {
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            Log.i("PyCode", "运行输出请查看: " + RUN_LOG)
        }, 2500)
    }
}