package net.rpcsx.utils

import net.rpcsx.utils.GeneralSettings.boolean
import net.rpcsx.utils.GeneralSettings.int

/**
 * Persisted, opt-in cosmetic theming for the running-game view (the Vulkan
 * render surface). Everything defaults OFF / neutral, so a fresh install renders
 * exactly as before. These are drawn by an app-side overlay (GameFrameOverlay)
 * that never touches the render surface, so they cannot affect emulation - the
 * worst a bad value can do is paint a corner the wrong colour.
 */
object GameViewTheme {
    private const val BLACK = 0xFF000000.toInt()
    private const val ACCENT = 0xFFA59DC4.toInt()

    var roundedCorners: Boolean
        get() = GeneralSettings["gv_rounded"].boolean(false)
        set(v) { GeneralSettings["gv_rounded"] = v }

    /** Corner radius in dp (applies to both the corner mask and the border). */
    var cornerRadiusDp: Int
        get() = GeneralSettings["gv_radius_dp"].int(28)
        set(v) { GeneralSettings["gv_radius_dp"] = v.coerceIn(0, 120) }

    /** Colour painted in the rounded-corner gaps (what shows "behind" the screen). */
    var cornerColor: Int
        get() = GeneralSettings["gv_corner_color"].int(BLACK)
        set(v) { GeneralSettings["gv_corner_color"] = v }

    var border: Boolean
        get() = GeneralSettings["gv_border"].boolean(false)
        set(v) { GeneralSettings["gv_border"] = v }

    var borderWidthDp: Int
        get() = GeneralSettings["gv_border_w_dp"].int(3)
        set(v) { GeneralSettings["gv_border_w_dp"] = v.coerceIn(0, 40) }

    var borderColor: Int
        get() = GeneralSettings["gv_border_color"].int(ACCENT)
        set(v) { GeneralSettings["gv_border_color"] = v }
}
