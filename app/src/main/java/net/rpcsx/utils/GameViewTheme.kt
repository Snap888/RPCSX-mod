package net.rpcsx.utils

import net.rpcsx.utils.GeneralSettings.boolean
import net.rpcsx.utils.GeneralSettings.int

/**
 * Persisted, opt-in cosmetic theming for the library game tiles (the home/list
 * view). Everything defaults OFF / neutral, so a fresh install looks unchanged.
 * Applied as a clip + border on the tile Card in GamesScreen - it never touches
 * the cover/icon aspect ratio (that lives on the inner image box), so logos are
 * not distorted.
 */
object GameViewTheme {
    private const val ACCENT = 0xFFA59DC4.toInt()

    /** Round the corners of every game tile in the library grid. */
    var roundedCorners: Boolean
        get() = GeneralSettings["gv_rounded"].boolean(false)
        set(v) { GeneralSettings["gv_rounded"] = v }

    /** Tile corner radius in dp. */
    var cornerRadiusDp: Int
        get() = GeneralSettings["gv_radius_dp"].int(16)
        set(v) { GeneralSettings["gv_radius_dp"] = v.coerceIn(0, 64) }

    /** Draw a coloured outline around each tile. */
    var border: Boolean
        get() = GeneralSettings["gv_border"].boolean(false)
        set(v) { GeneralSettings["gv_border"] = v }

    var borderWidthDp: Int
        get() = GeneralSettings["gv_border_w_dp"].int(2)
        set(v) { GeneralSettings["gv_border_w_dp"] = v.coerceIn(0, 16) }

    var borderColor: Int
        get() = GeneralSettings["gv_border_color"].int(ACCENT)
        set(v) { GeneralSettings["gv_border_color"] = v }
}
