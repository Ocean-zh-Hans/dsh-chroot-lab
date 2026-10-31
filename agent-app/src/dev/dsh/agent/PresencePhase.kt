package dev.dsh.agent

/** 对外可见的“在场阶段”。只描述人需要知道的状态，不承载任务/租约逻辑。 */
internal enum class PresencePhase(val label: String, val visible: Boolean) {
    IDLE("空闲", false),
    WORKING("AI 操作中", true),
    WAITING_HUMAN("待人工接管", true),
    PAUSED("暂停中", true),
    FINISHED("已结束", true),
    DISCONNECTED("已断开", true),
}
