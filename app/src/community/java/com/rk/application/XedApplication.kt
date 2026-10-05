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
     * PyCode 补丁：让 Termux 里的 Python 补全服务器（pylsp:8767）保持在线。
     *
     * 双保险：
     *  1) 写触发文件 /sdcard/Download/.lsp-trigger —— Termux 里的守护脚本（lsp.sh watch）
     *     会读到它并启动大脑（不依赖任何特殊权限）
     *  2) 再尝试 RUN_COMMAND 直接让 Termux 执行 lsp.sh start（需要危险权限
     *     com.termux.permission.RUN_COMMAND 已被授予时才生效）
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

        // 方式一：触发文件（可靠、无需权限）
        try {
            val trig = File("/sdcard/Download/.lsp-trigger")
            trig.parentFile?.mkdirs()
            trig.writeText(System.currentTimeMillis().toString())
            log("已写触发文件: " + trig.absolutePath)
        } catch (t: Throwable) {
            log("写触发文件失败: " + t.javaClass.simpleName + " " + t.message)
        }

        // 方式二：RUN_COMMAND（需要危险权限）
        try {
            val perm = try {
                checkSelfPermission("com.termux.permission.RUN_COMMAND").toString()
            } catch (t: Throwable) {
                "查询失败"
            }
            log("RUN_COMMAND权限(code)=" + perm)

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
                log("RUN_COMMAND 已发送")
            } catch (t: Throwable) {
                log("RUN_COMMAND 发送失败: " + t.javaClass.simpleName + " " + t.message)
            }
        } catch (t: Throwable) {
            log("异常: " + t.javaClass.simpleName + " " + t.message)
        }
    }
}
