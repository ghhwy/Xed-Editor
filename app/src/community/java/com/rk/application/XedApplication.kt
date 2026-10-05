package com.rk.application

import android.content.Intent
import android.util.Log
import com.rk.App
import com.rk.ExtensionFeature
import com.rk.TerminalFeature
import com.rk.ai.AiFeature
import com.rk.app.AppFlavour
import com.rk.feature.FeatureRegistry
import com.rk.git.GitFeature
import com.rk.runner.RunnerFeature
import java.io.File

/**
 * Application entry point for the `community` distribution.
 *
 * Registers every feature, including `ExtensionFeature` provided by the bundled
 * `:features:extensions` module.
 */
class XedApplication : App() {
    override fun onCreate() {
        // Publish the compile-time flavour (see app/build.gradle.kts) before any shared code runs.
        AppFlavour.init(BuildConfig.FLAVOUR)

        super.onCreate()
        // Register pluggable features
        FeatureRegistry.register(TerminalFeature())
        FeatureRegistry.register(ExtensionFeature())
        FeatureRegistry.register(RunnerFeature())
        FeatureRegistry.register(GitFeature())
        FeatureRegistry.register(AiFeature())
        // Initialize core features
        FeatureRegistry.initFeatures(this)

        startCompanionBrain()
    }

    /**
     * PyCode 补丁：自动在 Termux 里拉起 Python 补全服务器（pylsp，端口 8767）。
     * 全过程写日志到 files/pycode-brain.log，方便远程排查。
     */
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
            log("startCompanionBrain 被调用（App 启动）")

            val termuxInstalled = try {
                packageManager.getPackageInfo("com.termux", 0)
                true
            } catch (_: Throwable) {
                false
            }
            val perm = try {
                checkSelfPermission("com.termux.permission.RUN_COMMAND").toString()
            } catch (t: Throwable) {
                "查询失败:" + t.javaClass.simpleName
            }
            log("Termux已安装=" + termuxInstalled + "  RUN_COMMAND权限(code)=" + perm)

            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra(
                "com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/usr/bin/sh"
            )
            intent.putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("/data/data/com.termux/files/home/lsp.sh", "start")
            )
            intent.putExtra(
                "com.termux.RUN_COMMAND_WORKDIR",
                "/data/data/com.termux/files/home"
            )
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)

            try {
                startForegroundService(intent)
                log("startForegroundService 已发送")
            } catch (t: Throwable) {
                log("startForegroundService 失败: " + t.javaClass.simpleName + " " + t.message)
                try {
                    startService(intent)
                    log("startService 已发送（回退成功）")
                } catch (t2: Throwable) {
                    log("startService 也失败: " + t2.javaClass.simpleName + " " + t2.message)
                }
            }
        } catch (t: Throwable) {
            log("异常: " + t.javaClass.simpleName + " " + t.message)
        }
    }
}
