package io.github.lujinxin.nextep.workspace

import io.github.lujinxin.nextep.display.SlotGeometry
import io.github.lujinxin.nextep.display.WORKSPACE_SLOT_COUNT
import kotlin.math.roundToInt

/** One recorded layout shared by panels, main-task fitting and all preview displays. */
data class WorkspaceLayoutProfile(
    val geometry: WorkspaceGeometry,
    val densityDpi: Int,
    val gapPixels: Int,
) {
    data class Slot(val width: Int, val height: Int, val top: Int)
    data class Source(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    data class PreviewTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)

    init {
        require(densityDpi > 0 && gapPixels > 0)
        require(geometry.sidebarWidth > gapPixels)
    }

    private val leftGeometry = geometry.copy(sidebarSide = SidebarSide.LEFT)
    private val rightGeometry = geometry.copy(sidebarSide = SidebarSide.RIGHT)
    val canvas = SlotGeometry.forRecordedDisplay(geometry.availableWidth, geometry.availableHeight, densityDpi)
    // Apps lay out directly at the recorded usable resolution. Fit that complete
    // buffer into a slot; do not crop navigation pixels off an already laid-out app.
    val source = Source(0, 0, canvas.width, canvas.height)
    val slots: List<Slot> = run {
        val slotWidth = geometry.sidebarWidth - gapPixels
        val height = (geometry.sidebarLogicalHeight - gapPixels * (WORKSPACE_SLOT_COUNT - 1))
            .coerceAtLeast(WORKSPACE_SLOT_COUNT)
        val maxPerSlot = height / WORKSPACE_SLOT_COUNT
        val aspectHeight = (slotWidth.toDouble() * canvas.height / canvas.width)
            .roundToInt().coerceAtLeast(1)
        if (aspectHeight < maxPerSlot - 2) {
            val spacing = ((geometry.sidebarLogicalHeight - aspectHeight * WORKSPACE_SLOT_COUNT) /
                (WORKSPACE_SLOT_COUNT + 1)).coerceAtLeast(gapPixels)
            val totalUsed = aspectHeight * WORKSPACE_SLOT_COUNT + spacing * (WORKSPACE_SLOT_COUNT - 1)
            val startTop = ((geometry.sidebarLogicalHeight - totalUsed) / 2).coerceAtLeast(0)
            List(WORKSPACE_SLOT_COUNT) { index ->
                Slot(slotWidth, aspectHeight, startTop + index * (aspectHeight + spacing))
            }
        } else {
            val base = height / WORKSPACE_SLOT_COUNT
            val remainder = height % WORKSPACE_SLOT_COUNT
            List(WORKSPACE_SLOT_COUNT) { index ->
                Slot(
                    slotWidth,
                    base + if (index < remainder) 1 else 0,
                    index * (base + gapPixels) + minOf(index, remainder),
                )
            }
        }
    }

    fun geometry(side: SidebarSide): WorkspaceGeometry = if (side == SidebarSide.LEFT) leftGeometry else rightGeometry

    fun rotatedTo(rotation: Int): WorkspaceLayoutProfile =
        if (rotation == geometry.displayRotation) this else copy(geometry = geometry.rotatedTo(rotation))

    /** Undo TextureView's default stretching and fit the complete recorded canvas.
     * Slot bounds have the same aspect ratio apart from integer-pixel rounding. */
    fun previewTransform(width: Int, height: Int): PreviewTransform {
        require(width > 0 && height > 0)
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        return PreviewTransform(canvas.width * scale / width, canvas.height * scale / height,
            (width - source.width * scale) / 2f - source.left * scale,
            (height - source.height * scale) / 2f - source.top * scale)
    }

    fun encode(): String {
        val base = listOf(
            if (geometry.controlAtBottom) "6" else "5",
            geometry.screenWidth, geometry.screenHeight,
            geometry.topHeight, geometry.sidebarWidth, densityDpi, gapPixels,
            geometry.navigationInsets.left, geometry.navigationInsets.top,
            geometry.navigationInsets.right, geometry.navigationInsets.bottom,
            geometry.contentWidth, geometry.contentHeight, geometry.contentScale,
            canvas.width, canvas.height,
            source.left, source.top, source.right, source.bottom,
            *slots.flatMap { listOf(it.width, it.height, it.top) }.toTypedArray(),
            geometry.displayRotation,
        )
        return if (geometry.controlAtBottom) (base + "1").joinToString(",") else base.joinToString(",")
    }

    companion object {
        fun create(
            width: Int,
            height: Int,
            densityDpi: Int,
            insets: WorkspaceNavigationInsets,
            displayRotation: Int = 0,
            controlAtBottom: Boolean = false,
            compactControlHeightPx: Int? = null,
        ) = WorkspaceLayoutProfile(
            WorkspaceGeometry.forDisplay(
                width,
                height,
                density = densityDpi / 160f,
                navigationInsets = insets,
                displayRotation = displayRotation,
                controlAtBottom = controlAtBottom,
                compactControlHeightPx = compactControlHeightPx,
            ),
            densityDpi,
            (densityDpi / 160f).toInt().coerceAtLeast(1),
        )

        fun decode(encoded: String?): WorkspaceLayoutProfile? = runCatching {
            val p = encoded?.split(',') ?: return@runCatching null
            val version = p.firstOrNull() ?: return@runCatching null
            val expectedSize = when (version) {
                "2", "3", "4" -> 29
                "5" -> 30
                "6" -> 31
                else -> return@runCatching null
            }
            if (p.size != expectedSize) return@runCatching null
            val controlAtBottom = version == "6" && p[30] == "1"
            val profile = WorkspaceLayoutProfile(
                WorkspaceGeometry(
                    p[1].toInt(), p[2].toInt(),
                    p[3].toInt(), p[4].toInt(), SidebarSide.RIGHT,
                    WorkspaceNavigationInsets(p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt()),
                    displayRotation = if (version == "5" || version == "6") p[29].toInt() else 0,
                    controlAtBottom = controlAtBottom,
                ),
                p[5].toInt(), p[6].toInt(),
            )
            val expected = profile.encode().split(',').toMutableList()
            expected[0] = p[0]
            if (version in setOf("2", "3", "4")) expected.removeAt(29)
            if (p[0] == "2") {
                // Migrate the old full-screen canvas using its recorded insets.
                // This requires no WindowMetrics query or fresh calibration.
                val oldCanvas = SlotGeometry.forRecordedDisplay(profile.geometry.screenWidth,
                    profile.geometry.screenHeight, profile.densityDpi)
                expected[14] = oldCanvas.width.toString()
                expected[15] = oldCanvas.height.toString()
                expected[18] = oldCanvas.width.toString()
                expected[19] = oldCanvas.height.toString()
            }
            if (expected.joinToString(",") != encoded) return@runCatching null
            if (version in setOf("4", "5", "6")) profile else {
                // Recalculate the old slot proportions once using the already
                // recorded display and navigation mode; never resample metrics.
                create(profile.geometry.screenWidth, profile.geometry.screenHeight,
                    profile.densityDpi, profile.geometry.navigationInsets)
            }
        }.getOrNull()

    }
}
