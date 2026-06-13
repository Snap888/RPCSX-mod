package net.rpcsx.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A cosmetic overlay drawn ON TOP of the game surface but BELOW the pad controls.
 *
 * It does two things, both purely visual and both safe across every GPU /
 * compositor (unlike clipping a SurfaceView, which is what tends to break):
 *  - rounded corners: fills the area OUTSIDE a rounded rectangle with a chosen
 *    colour, so the square surface reads as having rounded corners;
 *  - a border: strokes the rounded rectangle outline in a chosen colour.
 *
 * The view never consumes touch events (it is not clickable/focusable), so input
 * still reaches the surface and the pad overlay untouched.
 */
class GameFrameOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var radiusPx = 0f
    private var drawCornerMask = false
    private var drawBorder = false

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val maskPath = Path()
    private val roundPath = Path()
    private val rect = RectF()

    init {
        // Purely decorative: do not steal input from the surface / pad overlay.
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density

    fun configure(
        roundedCorners: Boolean,
        cornerRadiusDp: Int,
        cornerColor: Int,
        border: Boolean,
        borderWidthDp: Int,
        borderColor: Int,
    ) {
        radiusPx = dp(cornerRadiusDp)
        drawCornerMask = roundedCorners && radiusPx > 0f
        maskPaint.color = cornerColor
        drawBorder = border && borderWidthDp > 0 && Color.alpha(borderColor) > 0
        borderPaint.color = borderColor
        borderPaint.strokeWidth = dp(borderWidthDp)
        // Nothing to draw at all -> stay fully out of the way.
        visibility = if (drawCornerMask || drawBorder) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (drawCornerMask) {
            // Area between the full rect and the rounded rect, filled with the
            // corner colour -> the surface's square corners are hidden.
            maskPath.reset()
            maskPath.addRect(0f, 0f, w, h, Path.Direction.CW)
            roundPath.reset()
            roundPath.addRoundRect(0f, 0f, w, h, radiusPx, radiusPx, Path.Direction.CW)
            maskPath.op(roundPath, Path.Op.DIFFERENCE)
            canvas.drawPath(maskPath, maskPaint)
        }

        if (drawBorder) {
            val inset = borderPaint.strokeWidth / 2f
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint)
        }
    }
}
