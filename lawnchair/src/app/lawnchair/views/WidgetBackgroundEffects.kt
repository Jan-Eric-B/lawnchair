package app.lawnchair.views

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.widget.RoundedCornerEnforcement
import kotlin.math.roundToInt

/**
 * Lawnchair: background opacity, blur and glass edge for app widgets.
 *
 * Applied to the view the widget marks as its background (`@android:id/background`).
 * The blur uses the platform's cross-window background blur, so it shows the wallpaper behind
 * the launcher window.
 */
class WidgetBackgroundEffects(private val host: ViewGroup) {

    private val density = host.resources.displayMetrics.density
    private val prefs = PreferenceManager2.getInstance(host.context)

    private val backgroundAlpha =
        (255 * prefs.widgetBackgroundOpacity.firstCached(prefs).coerceIn(0f, 1f)).roundToInt()
    private val blurRadius =
        (MAX_BLUR_DP * density * prefs.widgetBackgroundBlur.firstCached(prefs).coerceIn(0f, 1f)).roundToInt()
    private val glassEdge = prefs.widgetGlassEdge.firstCached(prefs)

    private val isActive = backgroundAlpha < 255 || blurRadius > 0 || glassEdge

    private var background: View? = null
    private val bounds = Rect()
    private val boundsF = RectF()
    private val edgeBounds = RectF()
    private var cornerRadius = 0f

    private var blurDrawable: Drawable? = null
    private var blurUnavailable = false

    private val edgePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = EDGE_WIDTH_DP * density
        }
    }
    private val sheenPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }

    // RemoteViews updates can recolor or replace the background without a new layout
    private val fadeListener = ViewTreeObserver.OnPreDrawListener {
        fadeBackground()
        true
    }

    fun onAttachedToWindow() {
        if (backgroundAlpha < 255) host.viewTreeObserver.addOnPreDrawListener(fadeListener)
    }

    fun onDetachedFromWindow() {
        host.viewTreeObserver.removeOnPreDrawListener(fadeListener)
        // The blur drawable belongs to the old window
        blurDrawable = null
    }

    fun onLayout(enforcedCornerRadius: Float) {
        if (!isActive) return
        background = RoundedCornerEnforcement.findBackground(host).takeIf { it !== host }
        val bg = background
        if (bg == null) {
            bounds.setEmpty()
            return
        }
        RoundedCornerEnforcement.computeRoundedRectangle(host, bg, bounds)
        boundsF.set(bounds)
        cornerRadius = outlineRadius(bg) ?: enforcedCornerRadius
        updateEdgeShaders()
        fadeBackground()
    }

    /** Draws behind the widget content. */
    fun drawBehind(canvas: Canvas) {
        if (bounds.isEmpty) return
        ensureBlurDrawable()?.let {
            it.bounds = bounds
            it.draw(canvas)
        }
        if (glassEdge) canvas.drawRoundRect(boundsF, cornerRadius, cornerRadius, sheenPaint)
    }

    /** Draws over the widget content. */
    fun drawOver(canvas: Canvas) {
        if (!glassEdge || bounds.isEmpty) return
        val inset = edgePaint.strokeWidth / 2
        val radius = (cornerRadius - inset).coerceAtLeast(0f)
        edgeBounds.set(boundsF)
        edgeBounds.inset(inset, inset)
        canvas.drawRoundRect(edgeBounds, radius, radius, edgePaint)
    }

    private fun fadeBackground() {
        if (backgroundAlpha == 255) return
        // No-op when the alpha is already applied
        background?.background?.mutate()?.alpha = backgroundAlpha
    }

    private fun outlineRadius(view: View): Float? {
        if (!view.clipToOutline) return null
        val outline = Outline()
        view.outlineProvider?.getOutline(view, outline) ?: return null
        return outline.radius.takeIf { it > 0f }
    }

    private fun updateEdgeShaders() {
        if (!glassEdge) return
        edgePaint.shader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.right, boundsF.bottom,
            intArrayOf(0x99FFFFFF.toInt(), 0x22FFFFFF, 0x08FFFFFF, 0x22FFFFFF, 0x66FFFFFF),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = LinearGradient(
            0f, boundsF.top, 0f, boundsF.top + boundsF.height() * 0.6f,
            0x1FFFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
    }

    private fun ensureBlurDrawable(): Drawable? {
        if (blurRadius <= 0 || blurUnavailable) return null
        blurDrawable?.let { return it }
        return try {
            val viewRoot = View::class.java.getMethod("getViewRootImpl").invoke(host)
                ?: return null
            val drawable = viewRoot.javaClass.getMethod("createBackgroundBlurDrawable")
                .invoke(viewRoot) as Drawable
            drawable.javaClass.getMethod("setBlurRadius", Int::class.java)
                .invoke(drawable, blurRadius)
            drawable.javaClass.getMethod("setCornerRadius", Float::class.java)
                .invoke(drawable, cornerRadius)
            drawable.also { blurDrawable = it }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Background blur unavailable", e)
            blurUnavailable = true
            null
        }
    }

    companion object {
        private const val TAG = "WidgetBackgroundEffects"
        private const val MAX_BLUR_DP = 40f
        private const val EDGE_WIDTH_DP = 1.2f
    }
}
