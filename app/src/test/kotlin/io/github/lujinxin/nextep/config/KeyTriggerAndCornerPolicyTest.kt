package io.github.lujinxin.nextep.config

import io.github.lujinxin.nextep.workspace.SidebarSide
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.WorkspaceLayoutProfile
import io.github.lujinxin.nextep.workspace.WorkspaceNavigationInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTriggerAndCornerPolicyTest {
    @Test
    fun defaultsAreSafeAndNormalized() {
        val config = WorkspaceTopConfig()
        assertFalse(config.keyTriggerEnabled)
        assertFalse(config.controlAtBottom)
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

    @Test
    fun compactTopAndBottomBarGeometryPreservesAspectRatioAndRoundTrips() {
        val topProfile = WorkspaceLayoutProfile.create(
            width = 1080,
            height = 2400,
            densityDpi = 480,
            insets = WorkspaceNavigationInsets(),
            controlAtBottom = false,
            compactControlHeightPx = 396,
        )
        assertFalse(topProfile.geometry.controlAtBottom)
        assertEquals(396, topProfile.geometry.topHeight)
        assertEquals(0, topProfile.geometry.controlTop)
        assertEquals(396, topProfile.geometry.contentTop)
        assertEquals(topProfile, WorkspaceLayoutProfile.decode(topProfile.encode()))

        val bottomProfile = WorkspaceLayoutProfile.create(
            width = 1080,
            height = 2400,
            densityDpi = 480,
            insets = WorkspaceNavigationInsets(),
            controlAtBottom = true,
            compactControlHeightPx = 360,
        )
        val g = bottomProfile.geometry(SidebarSide.LEFT)
        assertTrue(g.controlAtBottom)
        assertEquals(360, g.topHeight)
        assertEquals(0, g.contentTop)
        assertEquals(0, g.sidebarTop)
        assertEquals(2400 - 360, g.contentBottom)
        assertEquals(2400 - 360, g.controlTop)
        assertEquals(bottomProfile, WorkspaceLayoutProfile.decode(bottomProfile.encode()))
        // Main viewport keeps exact aspect ratio
        assertEquals(
            g.availableWidth.toFloat() / g.availableHeight,
            g.contentWidth.toFloat() / g.contentHeight,
            1f / g.contentHeight,
        )
        // Side slots fit cleanly inside the sidebar without overflow
        assertTrue(bottomProfile.slots.first().top >= 0)
        assertTrue(bottomProfile.slots.last().top + bottomProfile.slots.last().height <= g.sidebarLogicalHeight)
    }
}
