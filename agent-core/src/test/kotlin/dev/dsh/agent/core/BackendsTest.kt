package dev.dsh.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendsTest {
    @Test
    fun autoChainPrefersLowLatency() {
        assertEquals(listOf("a11y", "uia", "shell"), Backends.chain("tap"))
        assertEquals(listOf("a11y", "uia", "shell"), Backends.chain("press"))
        assertEquals(listOf("a11y", "uia", "shell"), Backends.chain("shot"))
    }

    @Test
    fun textOnlyOnA11y() {
        assertEquals(listOf("a11y"), Backends.chain("setText"))
        assertTrue(Backends.alternatives("a11y", "setText").isEmpty())
    }

    @Test
    fun readTreeNeverShell() {
        assertEquals(listOf("a11y", "uia"), Backends.chain("read"))
    }

    @Test
    fun pinnedBackendIgnoresChain() {
        assertEquals(listOf("shell"), Backends.chain("tap", "shell"))
        assertEquals(listOf("uia"), Backends.chain("tap", "uia"))
        assertTrue(Backends.chain("tap", "nope").isEmpty())
    }

    @Test
    fun alternativesSkipFailedBackend() {
        val alts = Backends.alternatives("a11y", "tap")
        assertEquals(listOf("uia", "shell"), alts.map { it.first })
        val alts2 = Backends.alternatives("uia", "tap")
        assertEquals(listOf("a11y", "shell"), alts2.map { it.first })
    }
}
