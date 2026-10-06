package com.rk.runner.runners

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rk.file.FileObject
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.runner.FileRunner
import java.io.File

/**
 * PyCode 补丁（第 20 轮）：编辑器里的「▶ 运行」现在**直接在内置终端里跑**。
 *
 *  - 输出实时显示在终端里（**不再写 _run_output.txt，也不再自动打开那个文件标签**）
 *  - 能输入、能用终端工具栏的 ▶/■ 按钮运行与中断
 *  - 后端仍是 Termux（真 python），工作目录 = 该脚本所在目录
 */
object TermuxRunner : FileRunner() {
    override val id: String = "termux_runner"

    override val label: String = "在内置终端里运行"

    override val description: String = "打开内置终端并运行（实时输出 / 可输入 / 可中断）"

    override fun getIcon(context: Context): Icon = Icon.ResourceIcon(drawables.run)

    override fun matcher(fileObject: FileObject): Boolean =
        fileObject.getName().endsWith(".py")

    override suspend fun run(activity: Activity, fileObject: FileObject) {
        val path = fileObject.getAbsolutePath()
        try {
            val i = Intent()
            i.setClassName(activity, "com.rk.pycode.terminal.PyCodeTerminalActivity")
            i.putExtra("file", path)
            i.putExtra("cwd", File(path).parent ?: "/sdcard/PythonProjects")
            activity.startActivity(i)
            Log.i("PyCode", "已在终端里运行: " + path)
        } catch (t: Throwable) {
            Log.e("PyCode", "打开内置终端失败", t)
        }
    }
}
