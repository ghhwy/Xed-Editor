package com.rk.application

import android.content.Intent
import android.util.Log
import com.rk.App
import com.rk.ExtensionFeature
import com.rk.ai.AiFeature
import com.rk.app.AppFlavour
import com.rk.commands.CommandProvider
import com.rk.commands.ToolbarConfiguration
import com.rk.feature.FeatureRegistry
import com.rk.git.GitFeature
import com.rk.pycode.TermuxTerminalCommand
import com.rk.runner.RunnerFeature
import com.rk.runner.RunnerManager
import com.rk.runner.runners.TermuxRunner
import java.io.File

/**
 * Application entry point for the `community` distribution.
 *
 * PyCode 改造：不再注册 TerminalFeature（自带终端/沙箱已整体移除），
 * 改为注册「终端 → Termux」命令 + 「在 Termux 里运行」运行器。
 */
class XedApplication : App() {
    override fun onCreate() {
        AppFlavour.init(BuildConfig.FLAVOUR)

        super.onCreate()
        FeatureRegistry.register(ExtensionFeature())
        FeatureRegistry.register(RunnerFeature())
        FeatureRegistry.register(GitFeature())
        FeatureRegistry.register(AiFeature())
        FeatureRegistry.initFeatures(this)

        // 「终端」入口 → Termux
        CommandProvider.registerCommand(TermuxTerminalCommand)
        ToolbarConfiguration.addGlobalToolbarCommand(TermuxTerminalCommand, index = 1)

        // 运行按钮 → Termux
        RunnerManager.registerRunner(TermuxRunner)

        startCompanionBrain()
    }

    /** PyCode 补丁：让 Termux 里的 pylsp（补全大脑）保持在线 */
    private fun startCompanionBrain() {
        val logFile = File(getExternalFilesDir(null) ?: filesDir, "pycode-brain.log")

        fun log(msg: String) {
            try {
                logFile.appendText("[" + System.currentTimeMillis() + "] " + msg + "\n")
            } catch (_: Throwable) {
            }
            Log.i("PyCode", msg)
        }

        try {
            val trig = File("/sdcard/Download/.lsp-trigger")
            trig.parentFile?.mkdirs()
            trig.writeText(System.currentTimeMillis().toString())
            log("已写触发文件: " + trig.absolutePath)
        } catch (t: Throwable) {
            log("写触发文件失败: " + t.javaClass.simpleName + " " + t.message)
        }

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
                arrayOf("/data/data/com.termux/files/home/lsp.sh", "daemon")
            )
            i.putExtra(
                "com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home"
            )
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            try {
                startForegroundService(i)
                log("RUN_COMMAND 已发送")
            } catch (t: Throwable) {
                log("RUN_COMMAND 发送失败: " + t.javaClass.simpleName + " " + t.message)
            }
        } catch (t: Throwable) {
            log("异常: " + t.javaClass.simpleName + " " + t.message)
        }
    }
}
