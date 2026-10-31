package dev.dsh.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSessionTest {

    private fun snapshot(g: Long, seq: Long = 1, pkg: String = "com.example.app"): Snapshot {
        val b = SnapshotBuilder(g = g, seq = seq, pkg = pkg)
        val root = b.add("android.widget.FrameLayout", Rect(0, 0, 1080, 2400))
        b.add("android.widget.TextView", Rect(40, 100, 500, 180), parent = root, text = "页面")
        b.add("android.widget.Button", Rect(40, 200, 300, 280), parent = root, text = "确定", flags = Flags.CLICKABLE)
        return b.build()
    }

    private fun lease(
        expiresAt: Long = 10_000,
        scopes: Set<Scope> = Scope.entries.toSet(),
        readonly: Boolean = false,
    ) = Lease("agent-1", scopes, expiresAt, readonly = readonly)

    private fun started(
        expiresAt: Long = 10_000,
        scopes: Set<Scope> = Scope.entries.toSet(),
        readonly: Boolean = false,
    ): DeviceSession {
        val session = DeviceSession("session-1", clock = { 0 })
        session.start(lease(expiresAt, scopes, readonly), now = 0)
        return session
    }

    @Test
    fun observeBindsPageAndRefs() {
        val session = started()
        val observation = session.observe(snapshot(3), now = 1)

        assertEquals(PageToken(3, 1), observation.page)
        assertEquals("com.example.app", observation.pkg)
        assertNotNull(observation.view.refOf(0))
        assertEquals(observation.page, session.state.page)
        assertFalse(session.state.dirty)
    }

    @Test
    fun uiEffectInvalidatesAndRequiresReobservation() {
        val session = started()
        val observation = session.observe(snapshot(1), now = 1)
        val action = Action(
            op = "open",
            uiEffect = UiEffect.CERTAIN,
            expectedPage = observation.page,
            scope = Scope.SHELL,
        )

        session.prepare(action, now = 2)
        val completion = session.complete(action, ok = true, backend = "shell", detail = "am start", now = 3)

        assertTrue(completion.requiresObservation)
        assertNull(session.state.page)
        assertNull(completion.page)

        val error = assertFailsWith<SessionException> {
            session.prepare(Action(op = "tap", expectedPage = observation.page), now = 4)
        }
        assertEquals("STALE_PAGE", error.code)
    }

    @Test
    fun shellWithoutUiEffectKeepsPage() {
        val session = started()
        val observation = session.observe(snapshot(1), now = 1)
        val action = Action(op = "content", uiEffect = UiEffect.NONE, scope = Scope.SHELL)

        session.prepare(action, now = 2)
        val completion = session.complete(action, ok = true, backend = "shell", now = 3)

        assertFalse(completion.requiresObservation)
        assertEquals(observation.page, session.state.page)
    }

    @Test
    fun stalePageIsRejected() {
        val session = started()
        session.observe(snapshot(1), now = 1)
        session.observe(snapshot(2), now = 2)

        val error = assertFailsWith<SessionException> {
            session.prepare(Action(op = "tap", expectedPage = PageToken(1, 1)), now = 3)
        }
        assertEquals("STALE_PAGE", error.code)
    }

    @Test
    fun expiredLeaseIsRejected() {
        val session = started(expiresAt = 10)
        val error = assertFailsWith<SessionException> {
            session.prepare(Action(op = "tap"), now = 11)
        }
        assertEquals("LEASE_EXPIRED", error.code)
    }

    @Test
    fun readonlyLeaseCannotAct() {
        val session = started(scopes = setOf(Scope.UI_READ), readonly = true)
        session.observe(snapshot(1), now = 1)

        val error = assertFailsWith<SessionException> {
            session.prepare(Action(op = "tap"), now = 2)
        }
        assertEquals("FORBIDDEN", error.code)
    }

    @Test
    fun revokedLeaseIsRejected() {
        val session = started()
        session.revokeLease()

        val error = assertFailsWith<SessionException> {
            session.prepare(Action(op = "tap"), now = 2)
        }
        assertEquals("NO_LEASE", error.code)
    }

    @Test
    fun handoffStateMachineRequiresFreshObservation() {
        val session = started()
        session.observe(snapshot(1), now = 1)

        val paused = session.pauseForHuman("captcha", now = 2)
        assertEquals(SessionPhase.PAUSED_FOR_HUMAN, paused.phase)
        assertEquals("HANDOFF_PAUSED", assertFailsWith<SessionException> {
            session.prepare(Action(op = "tap"), now = 3)
        }.code)

        session.humanTakeover(now = 4)
        session.resumeFromHuman(now = 5)
        val fresh = session.observe(snapshot(2), now = 6)
        session.completeResume(fresh, now = 7)

        assertEquals(SessionPhase.RUNNING, session.state.phase)
        session.prepare(Action(op = "tap", expectedPage = fresh.page), now = 8)
    }

    @Test
    fun receiptCollectsSteps() {
        val session = started()
        session.beginTask("task-demo", now = 1)
        session.observe(snapshot(1), now = 2)
        val action = Action(op = "content", uiEffect = UiEffect.NONE, scope = Scope.SHELL)
        session.prepare(action, now = 3)
        session.complete(action, ok = true, backend = "shell", detail = "query", now = 4)

        val receipt = session.finishTask(now = 5)
        assertEquals("task-demo", receipt.taskId)
        assertEquals(1, receipt.steps.size)
        assertEquals("content", receipt.steps[0].op)
        assertEquals(1, session.receipts().size)
    }

    @Test
    fun scopeIsInferredFromSemanticOp() {
        val session = started()
        assertEquals(Scope.TEXT, session.scopeFor("setText"))
        assertEquals(Scope.UI_ACT, session.scopeFor("tap"))
        assertEquals(Scope.NOTIFY, session.scopeFor("notif.reply"))
        assertEquals(Scope.SHELL, session.scopeFor("open"))
    }
}
