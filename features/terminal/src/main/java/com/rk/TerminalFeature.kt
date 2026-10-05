package com.rk

import android.app.Application
import android.content.Intent
import com.rk.commands.CommandProvider
import com.rk.commands.ToolbarConfiguration
import com.rk.commands.global.TerminalCommand
import com.rk.feature.Feature
import com.rk.feature.FeatureRegistry
import com.rk.feature.FeatureToggle
import com.rk.file.FileObject
import com.rk.file.FileWrapper
import com.rk.filetree.FileAction
import com.rk.filetree.FileActionContext
import com.rk.filetree.FileActionProvider
import com.rk.filetree.FileActionType
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.utils.toast

/**
 * PyCode 改造版 TerminalFeature
 *
 *  - 所有「终端」入口都跳转到 Termux 应用（不再用自带 PRoot 沙箱）
 *  - 不再注册：终端设置 UI、UniversalRunner、内置沙箱 LSP 服务器、沙箱进程提供者
 */
class TerminalFeature : Feature {
    override val toggle =
        FeatureToggle(
            name = strings.terminal_feature.getString(),
            key = "feature_terminal",
            default = true,
            icon = Icon.ResourceIcon(drawables.terminal),
        )

    override fun init(application: Application) {
        // 文件树里「在终端中打开」：照旧注册，但行为改成跳 Termux
        FileActionProvider.registerAction(TerminalAction)

        // 所有走 TerminalLauncher 的地方（运行器、终端相关命令）都改成打开 Termux
        TerminalLauncher.handler = { activity, _, _, _, _, _, _, _ ->
            openTermux(activity)
        }

        // 不再提供沙箱进程
        SandboxedProcessRegistry.provider = null

        // 注册全局「终端」命令（工具栏 / 命令面板）→ 行为是打开 Termux
        CommandProvider.registerCommand(TerminalCommand)
        ToolbarConfiguration.addGlobalToolbarCommand(TerminalCommand, index = 1)
    }

    override fun dispose(application: Application) {
        FileActionProvider.unregisterAction(TerminalAction)
        TerminalLauncher.handler = null
        SandboxedProcessRegistry.provider = null
        CommandProvider.unregisterCommand(TerminalCommand)
        ToolbarConfiguration.removeGlobalToolbarCommand(TerminalCommand)
    }

    private fun openTermux(activity: android.app.Activity) {
        try {
            val i = Intent()
            i.setClassName("com.termux", "com.termux.app.TermuxActivity")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        } catch (t: Throwable) {
            toast("没有找到 Termux 应用，请先安装 Termux")
        }
    }
}

object TerminalAction : FileAction() {
    override val icon = Icon.ResourceIcon(drawables.terminal)
    override val title = strings.open_in_terminal.getString()

    override suspend fun execute(context: FileActionContext) {
        // PyCode 改造：跳转到 Termux
        try {
            val i = Intent()
            i.setClassName("com.termux", "com.termux.app.TermuxActivity")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.context.startActivity(i)
        } catch (t: Throwable) {
            toast("没有找到 Termux 应用")
        }
    }

    override suspend fun isSupported(file: FileObject, root: FileObject?): Boolean {
        return file is FileWrapper && FeatureRegistry.isEnabled("feature_terminal")
    }

    override val type = FileActionType(file = false, folder = true, rootFolder = true)
}