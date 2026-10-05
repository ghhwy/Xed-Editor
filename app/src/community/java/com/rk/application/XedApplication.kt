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
     * 依赖：Termux 已开 allow-external-apps=true（已为用户配好），lsp.sh 幂等。
     */
    private fun startCompanionBrain() {
        try {
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
            startForegroundService(intent)
            Log.i("PyCode", "已请求 Termux 启动补全大脑")
        } catch (t: Throwable) {
            Log.e("PyCode", "拉起补全大脑失败", t)
        }
    }
}
