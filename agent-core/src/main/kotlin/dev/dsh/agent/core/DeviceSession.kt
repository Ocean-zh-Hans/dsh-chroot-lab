package dev.dsh.agent.core

/**
 * 页面代次。g 是全局代次，seq 是同一 g 内的快照序号。
 * ref 只在同一 page 内有效；跨页必须由 facade 重新绑定。
 */
data class PageToken(val g: Long, val seq: Long) {
    init {
        require(g >= 0) { "g must be >= 0" }
        require(seq >= 0) { "seq must be >= 0" }
    }
}

/** 一次观察结果。view 持有本次快照的 ref 映射。 */
data class Observation(
    val sessionId: String,
    val page: PageToken,
    val pkg: String,
    val activity: String,
    val keyboard: Boolean,
    val view: SnapshotView,
    val source: String,
    val at: Long,
) {
    val snapshot: Snapshot get() = view.snapshot
    val nodes: List<Node> get() = snapshot.nodes
}

/** 动作对 UI 的影响。shell 动作若可能改变前台/页面，必须显式标 possible/certain。 */
enum class UiEffect {
    NONE,
    POSSIBLE,
    CERTAIN;

    companion object {
        fun parse(value: String?): UiEffect = when (value?.trim()?.lowercase()) {
            null, "", "none" -> NONE
            "possible", "maybe" -> POSSIBLE
            "certain", "yes", "ui" -> CERTAIN
            else -> throw SessionException("INVALID_PARAMS", "unknown uiEffect: $value")
        }
    }
}

/** 语义动作的后端归属；AUTO 由 facade 按能力表选择。 */
enum class BackendKind {
    AUTO,
    APK,
    SHELL,
    SESSION,
}

/** 授权 scope。readonly lease 只允许 UI_READ。 */
enum class Scope(val wire: String) {
    UI_READ("ui.read"),
    UI_ACT("ui.act"),
    TEXT("text"),
    NOTIFY("notify"),
    CLIPBOARD("clipboard"),
    SHELL("shell");

    companion object {
        fun parse(value: String): Scope = entries.firstOrNull { it.wire == value || it.name.equals(value, true) }
            ?: throw SessionException("INVALID_PARAMS", "unknown scope: $value")
    }
}

/** 租约：人类把身体借给 agent 的时限与范围。 */
data class Lease(
    val agentId: String,
    val scopes: Set<Scope>,
    val expiresAt: Long,
    val apps: Set<String> = emptySet(),
    val readonly: Boolean = false,
) {
    fun active(now: Long): Boolean = now < expiresAt

    fun allows(scope: Scope, now: Long, pkg: String? = null): Boolean {
        if (!active(now)) return false
        if (scope !in scopes) return false
        if (readonly && scope != Scope.UI_READ) return false
        if (apps.isNotEmpty() && (pkg.isNullOrEmpty() || pkg !in apps)) return false
        return true
    }
}

/** 动作。where 只在需要按条件定位时使用；ref 与 where 至少一个由调用方提供。 */
data class Action(
    val op: String,
    val ref: String? = null,
    val where: Where? = null,
    val text: String? = null,
    val via: String = "auto",
    val uiEffect: UiEffect = UiEffect.NONE,
    val expectedPage: PageToken? = null,
    val timeoutMs: Long = 0,
    val idempotencyKey: String? = null,
    val pkg: String? = null,
    val scope: Scope? = null,
)

/** 动作执行后的回填。requiresObservation=true 时调用方必须 observe 后再发下一个 APK 动作。 */
data class Completion(
    val requiresObservation: Boolean,
    val page: PageToken?,
    val step: ReceiptStep,
)

data class ReceiptStep(
    val at: Long,
    val op: String,
    val backend: String,
    val uiEffect: UiEffect,
    val ok: Boolean,
    val detail: String = "",
)

data class Receipt(
    val taskId: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val steps: List<ReceiptStep> = emptyList(),
    val failure: String? = null,
)

/** 会话状态。g/seq、前台、键盘、剪贴板摘要、lease、handoff 都在这里。 */
class StateStore {
    var page: PageToken? = null
    var pkg: String = ""
    var activity: String = ""
    var keyboard: Boolean = false
    var clipboard: String = ""
    var phase: SessionPhase = SessionPhase.STOPPED
    var lease: Lease? = null
    var handoff: Handoff = Handoff()
    var lastObservation: Observation? = null
    var lastAction: Action? = null
    var lastInvalidateReason: String = ""
    var dirty: Boolean = true
}

enum class SessionPhase {
    STOPPED,
    RUNNING,
    PAUSED_FOR_HUMAN,
    HUMAN_CONTROL,
    RESUMING,
}

/** 交接状态机。RUNNING ↔ PAUSED_FOR_HUMAN → HUMAN_CONTROL → RESUMING → RUNNING。 */
data class Handoff(
    val phase: SessionPhase = SessionPhase.STOPPED,
    val reason: String = "",
    val since: Long = 0,
    val expiresAt: Long = 0,
) {
    val active: Boolean get() = phase == SessionPhase.PAUSED_FOR_HUMAN || phase == SessionPhase.HUMAN_CONTROL || phase == SessionPhase.RESUMING
}

class SessionException(val code: String, message: String) : RuntimeException(message)

/**
 * 跨后端会话的纯逻辑状态机。不依赖 Android，APK 与 shell 共用同一份 page/lease/handoff 状态。
 *
 * 典型调用：
 *   observe(snapshot) → prepare(action) → 执行 → complete(action, ...) → 若 requiresObservation 则 observe(...)
 */
class DeviceSession(
    val sessionId: String,
    private val refs: RefStore = RefStore(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val state = StateStore()
    private val receipts = ArrayList<Receipt>(4)
    private var activeReceipt: Receipt? = null
    private var receiptCounter = 0

    @Synchronized
    fun start(lease: Lease, now: Long = clock()): DeviceSession {
        if (state.phase != SessionPhase.STOPPED) {
            throw SessionException("INVALID_STATE", "session already started: ${state.phase}")
        }
        if (!lease.active(now)) throw SessionException("LEASE_EXPIRED", "lease already expired")
        state.lease = lease
        state.phase = SessionPhase.RUNNING
        state.handoff = Handoff(SessionPhase.RUNNING, "started", now, lease.expiresAt)
        state.dirty = true
        return this
    }

    /** 绑定一次快照；任何后端抓到的树都可以走这里。 */
    @Synchronized
    fun observe(snapshot: Snapshot, source: String = "apk", now: Long = clock()): Observation {
        if (state.phase == SessionPhase.STOPPED) {
            throw SessionException("NOT_RUNNING", "session is stopped; start with a lease first")
        }
        val view = refs.bind(snapshot)
        val page = PageToken(snapshot.g, snapshot.seq)
        state.page = page
        state.pkg = snapshot.pkg
        state.activity = snapshot.activity
        state.keyboard = snapshot.keyboard
        state.dirty = false
        val observation = Observation(sessionId, page, snapshot.pkg, snapshot.activity, snapshot.keyboard, view, source, now)
        state.lastObservation = observation
        return observation
    }

    /** UI 可能已变；主动作废旧 page，下一次 APK 动作前必须 observe。 */
    @Synchronized
    fun invalidate(reason: String = "") {
        state.page = null
        state.dirty = true
        state.lastObservation = null
        state.lastInvalidateReason = reason
    }

    /** 执行前校验：lease、page 代次、handoff 状态、scope。返回原 action 便于链式调用。 */
    @Synchronized
    fun prepare(action: Action, now: Long = clock()): Action {
        val lease = state.lease ?: throw SessionException("NO_LEASE", "no active lease")
        if (!lease.active(now)) throw SessionException("LEASE_EXPIRED", "lease expired at ${lease.expiresAt}")
        if (state.phase != SessionPhase.RUNNING) {
            throw SessionException("HANDOFF_PAUSED", "session is not running: ${state.phase}")
        }
        val expected = action.expectedPage
        if (expected != null) {
            val current = state.page ?: throw SessionException("STALE_PAGE", "page is invalid; observe again")
            if (current != expected) {
                throw SessionException("STALE_PAGE", "expected g=${expected.g} seq=${expected.seq}, current g=${current.g} seq=${current.seq}")
            }
        }
        val scope = action.scope ?: scopeFor(action.op)
        if (scope != Scope.UI_READ && scope != Scope.SHELL && scope != Scope.NOTIFY && scope != Scope.CLIPBOARD && state.page == null) {
            throw SessionException("STALE_PAGE", "no current page; observe again before ${action.op}")
        }
        if (!lease.allows(scope, now, action.pkg ?: state.pkg)) {
            throw SessionException("FORBIDDEN", "lease ${lease.agentId} does not allow ${scope.wire}")
        }
        return action
    }

    /**
     * 动作执行后记账。uiEffect != NONE 时无条件 invalidate（即使执行失败，页面也可能已变）。
     * 返回 requiresObservation 给 facade；不要在未刷新时把旧 ref 交给模型。
     */
    @Synchronized
    fun complete(
        action: Action,
        ok: Boolean,
        backend: String,
        detail: String = "",
        now: Long = clock(),
    ): Completion {
        state.lastAction = action
        if (action.uiEffect != UiEffect.NONE) invalidate("action ${action.op} may have changed UI")
        val step = ReceiptStep(now, action.op, backend, action.uiEffect, ok, detail)
        activeReceipt = (activeReceipt ?: Receipt(nextTaskId(), now)).let { it.copy(steps = it.steps + step) }
        val requires = action.uiEffect != UiEffect.NONE || state.page == null
        return Completion(requires, state.page, step)
    }

    @Synchronized
    fun beginTask(taskId: String? = null, now: Long = clock()): Receipt {
        if (activeReceipt != null) throw SessionException("INVALID_STATE", "a task is already active")
        val receipt = Receipt(taskId ?: nextTaskId(), now)
        activeReceipt = receipt
        return receipt
    }

    @Synchronized
    fun finishTask(failure: String? = null, now: Long = clock()): Receipt {
        val current = activeReceipt ?: throw SessionException("INVALID_STATE", "no active task")
        val receipt = current.copy(finishedAt = now, failure = failure)
        activeReceipt = null
        receipts.add(receipt)
        return receipt
    }

    @Synchronized
    fun receipts(): List<Receipt> = receipts.toList()

    @Synchronized
    fun revokeLease() {
        state.lease = null
        invalidate("lease revoked")
        state.phase = SessionPhase.STOPPED
        state.handoff = Handoff(SessionPhase.STOPPED, "lease revoked", state.handoff.since)
    }

    @Synchronized
    fun pauseForHuman(reason: String, now: Long = clock()): Handoff {
        if (state.phase != SessionPhase.RUNNING) {
            throw SessionException("INVALID_STATE", "cannot pause from ${state.phase}")
        }
        state.phase = SessionPhase.PAUSED_FOR_HUMAN
        state.handoff = Handoff(SessionPhase.PAUSED_FOR_HUMAN, reason, now, state.lease?.expiresAt ?: 0)
        return state.handoff
    }

    @Synchronized
    fun humanTakeover(now: Long = clock()): Handoff {
        if (state.phase != SessionPhase.PAUSED_FOR_HUMAN) {
            throw SessionException("INVALID_STATE", "cannot take over from ${state.phase}")
        }
        state.phase = SessionPhase.HUMAN_CONTROL
        state.handoff = state.handoff.copy(phase = SessionPhase.HUMAN_CONTROL, since = now)
        return state.handoff
    }

    @Synchronized
    fun resumeFromHuman(now: Long = clock()): Handoff {
        if (state.phase != SessionPhase.HUMAN_CONTROL) {
            throw SessionException("INVALID_STATE", "cannot resume from ${state.phase}")
        }
        state.phase = SessionPhase.RESUMING
        state.handoff = state.handoff.copy(phase = SessionPhase.RESUMING, since = now)
        return state.handoff
    }

    /** 人类操作完成后必须先 observe 新页面，再 completeResume；避免拿旧 ref 恢复。 */
    @Synchronized
    fun completeResume(observation: Observation, now: Long = clock()): Handoff {
        if (state.phase != SessionPhase.RESUMING) {
            throw SessionException("INVALID_STATE", "cannot complete resume from ${state.phase}")
        }
        if (observation.page != state.page) {
            throw SessionException("STALE_PAGE", "resume observation is not current page")
        }
        state.phase = SessionPhase.RUNNING
        state.handoff = state.handoff.copy(phase = SessionPhase.RUNNING, since = now)
        return state.handoff
    }

    @Synchronized
    fun stop(reason: String = "") {
        state.phase = SessionPhase.STOPPED
        state.handoff = Handoff(SessionPhase.STOPPED, reason, state.handoff.since)
        invalidate(reason)
    }

    fun scopeFor(op: String): Scope = when (op) {
        "observe", "view", "nodes", "diff", "wait", "expect", "shot", "screenshot", "read" -> Scope.UI_READ
        "setText", "appendText", "clearText", "fill", "input" -> Scope.TEXT
        "notify", "notif", "notif.list", "notif.history", "notif.dismiss", "notif.reply" -> Scope.NOTIFY
        "clipboard", "ui.clipboard", "clipboard.history", "copy", "cut", "paste" -> Scope.CLIPBOARD
        "shell", "open", "attach", "am", "pm", "content" -> Scope.SHELL
        else -> Scope.UI_ACT
    }

    private fun nextTaskId(): String = "task-" + (++receiptCounter)
}
