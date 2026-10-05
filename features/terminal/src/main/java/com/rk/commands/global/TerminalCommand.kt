package com.rk.commands.global

import android.content.Intent
import android.view.KeyEvent
import com.rk.commands.ActionContext
import com.rk.commands.GlobalCommand
import com.rk.commands.KeyCombination
import com.rk.feature.FeatureRegistry
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings

/** PyCode 改造：终端命令 → 直接跳转到 Termux 应用 */
object TerminalCommand : GlobalCommand() {
    override val id: String = "global.terminal"

    override fun getLabel(): String = strings.terminal.getString()

    override fun execute(context: ActionContext) {
        val activity = context.currentActivity
        try {
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.TermuxActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    override fun isSupported(): Boolean = FeatureRegistry.isEnabled("feature_terminal")

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.terminal)

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_J, ctrl = true)
}