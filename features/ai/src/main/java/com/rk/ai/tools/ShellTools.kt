package com.rk.ai.tools

/**
 * PyCode 改造：原来的 Shell 工具依赖自带 PRoot 沙箱（已整体移除）。
 * 这里保留空实现，AI 功能可用，但不再提供沙箱 shell 工具。
 */
object ShellTools {
    fun all(): List<AiTool> = emptyList()
}
