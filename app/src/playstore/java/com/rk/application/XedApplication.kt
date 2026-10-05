package com.rk.application

import com.rk.App
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

/**
 * Application entry point for the `playstore` distribution.
 *
 * PyCode 改造：自带终端/沙箱已移除，终端入口改为跳转 Termux。
 */
class XedApplication : App() {
    override fun onCreate() {
        AppFlavour.init(BuildConfig.FLAVOUR)

        super.onCreate()

        FeatureRegistry.register(RunnerFeature())
        FeatureRegistry.register(GitFeature())
        FeatureRegistry.register(AiFeature())
        FeatureRegistry.initFeatures(this)

        CommandProvider.registerCommand(TermuxTerminalCommand)
        ToolbarConfiguration.addGlobalToolbarCommand(TermuxTerminalCommand, index = 1)
        RunnerManager.registerRunner(TermuxRunner)
    }
}
