package com.baiyunkeys.core.text

import com.baiyunkeys.core.model.DoorConfig

/**
 * 门禁参数文本导出。
 *
 * 格式与 iOS `ConfigView.exportText(_:)` **逐字一致**（含全角冒号），多条之间用空行分隔 ——
 * 这正是 [DoorTextParser] 的分条规则，所以「导出 → 导入」必须能完整往返。
 *
 * 注意：标题用的是 `displayName`，空名字的门禁导出后是「未命名门禁」，导回时它会变成真名字。
 * iOS 就是这么导的，保持一致。
 */
object DoorTextExporter {

    fun export(door: DoorConfig): String = buildString {
        append("门禁名称：").append(door.displayName).append('\n')
        append("门禁 MAC：").append(door.mac).append('\n')
        append("门禁 Key：").append(door.key).append('\n')
        append("蓝牙名称：").append(door.bluetoothName)
    }

    /** 多条：每条之间一个空行。 */
    fun exportAll(doors: List<DoorConfig>): String = doors.joinToString("\n\n") { export(it) }
}
