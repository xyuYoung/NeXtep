package io.github.lujinxin.nextep.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyTriggerAndCornerPolicyTest {
    @Test
    fun defaultsAreSafeAndNormalized() {
        val config = WorkspaceTopConfig()
        assertFalse(config.keyTriggerEnabled)
        assertEquals(0, config.keyTriggerKeyCode)
        assertEquals(WorkspaceConfigContract.KEY_TRIGGER_MODE_SINGLE, config.keyTriggerMode)
        assertEquals(12, config.windowCornerRadiusDp)
    }

    @Test
    fun keyCodeAndModeNormalization() {
        assertEquals(0, WorkspaceConfigContract.normalizeKeyTriggerKeyCode(-5))
        assertEquals(25, WorkspaceConfigContract.normalizeKeyTriggerKeyCode(25))
        assertEquals(1024, WorkspaceConfigContract.normalizeKeyTriggerKeyCode(9999))
        assertEquals(WorkspaceConfigContract.KEY_TRIGGER_MODE_SINGLE, WorkspaceConfigContract.normalizeKeyTriggerMode(null))
        assertEquals(WorkspaceConfigContract.KEY_TRIGGER_MODE_SINGLE, WorkspaceConfigContract.normalizeKeyTriggerMode("UNKNOWN"))
        assertEquals(WorkspaceConfigContract.KEY_TRIGGER_MODE_DOUBLE, WorkspaceConfigContract.normalizeKeyTriggerMode("DOUBLE"))
        assertEquals(WorkspaceConfigContract.KEY_TRIGGER_MODE_LONG, WorkspaceConfigContract.normalizeKeyTriggerMode("LONG"))
    }

    @Test
    fun cornerRadiusClamping() {
        assertEquals(0, WorkspaceConfigContract.normalizeWindowCornerRadiusDp(-10))
        assertEquals(12, WorkspaceConfigContract.normalizeWindowCornerRadiusDp(12))
        assertEquals(24, WorkspaceConfigContract.normalizeWindowCornerRadiusDp(99))
    }
}
