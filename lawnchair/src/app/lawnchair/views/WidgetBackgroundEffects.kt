package app.lawnchair.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.launcher3.widget.BaseLauncherAppWidgetHostView
import com.android.launcher3.widget.RoundedCornerEnforcement
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * Applies the widget background settings to an app widget.
 *
 * Only the view the widget declares as its background (`@android:id/background`) is changed.
 * Widgets without exactly one such view are left alone.
 *
 * The blur uses the platform's cross-window background blur (Android 12+), so it blurs the
 * wallpaper behind the launcher window. When the system turns window blurs off later, for
 * example in battery saver, the region simply stays unblurred.
 */
class WidgetBackgroundEffects private constructor(
    private val host: BaseLauncherAppWidgetHostView,
    private val backgroundAlpha: Int,
    private var blurRadius: Int,
    private val glassEdge: Boolean,
) : View.OnAttachStateChangeListener,
    ViewTreeObserver.OnGlobalLayoutListener,
    ViewTreeObserver.OnPreDrawListener {

    private var observer: ViewTreeObserver? = null
    private var background: View? = null
    private val bounds = Rect()
    private var cornerRadius = 0f
    private var blur: BackgroundBlurDrawable? = null

    // Glass edge (personal build): rim over the content, sheen behind it
    private val boundsF = RectF()
    private val edgeBounds = RectF()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onViewAttachedToWindow(v: View) {
        observer = v.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(this)
            if (backgroundAlpha < 255) it.addOnPreDrawListener(this)
        }
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(this)
            it.removeOnPreDrawListener(this)
        }
        observer = null
        // The blur drawable belongs to the window it was created for
        blur = null
    }

    // RemoteViews updates can replace the widget's views, so look the background up after layouts
    override fun onGlobalLayout() {
        val bg = findDeclaredBackground(host)
        background = bg
        val oldBounds = Rect(bounds)
        val oldRadius = cornerRadius
        if (bg == null) {
            bounds.setEmpty()
        } else {
            RoundedCornerEnforcement.computeRoundedRectangle(host, bg, bounds)
            cornerRadius = cornerRadiusOf(bg)
        }
        if (bounds != oldBounds || cornerRadius != oldRadius) {
            blur?.setCornerRadius(cornerRadius)
            updateGlassShaders()
            host.invalidate()
        }
    }

    // RemoteViews updates can also recolor the background drawable without a new layout,
    // which resets its alpha, so it is applied again before every draw.
    override fun onPreDraw(): Boolean {
        fadeBackground()
        return true
    }

    /** Draws the widget content through [drawContent], with the blur behind it. */
    fun dispatchDraw(canvas: Canvas, drawContent: Consumer<Canvas>) {
        // The blur drawable records a RenderNode, which needs a hardware canvas
        if (!bounds.isEmpty && canvas.isHardwareAccelerated) {
            blurDrawable()?.let {
                it.bounds = bounds
                it.draw(canvas)
            }
        }
        if (glassEdge && !bounds.isEmpty) {
            canvas.drawRoundRect(boundsF, cornerRadius, cornerRadius, sheenPaint)
        }
        drawContent.accept(canvas)
        if (glassEdge && !bounds.isEmpty) {
            val inset = edgePaint.strokeWidth / 2
            val radius = (cornerRadius - inset).coerceAtLeast(0f)
            edgeBounds.set(boundsF)
            edgeBounds.inset(inset, inset)
            canvas.drawRoundRect(edgeBounds, radius, radius, edgePaint)
        }
    }

    private fun updateGlassShaders() {
        if (!glassEdge) return
        boundsF.set(bounds)
        edgePaint.strokeWidth = EDGE_WIDTH_DP * host.resources.displayMetrics.density
        edgePaint.shader = LinearGradient(
            boundsF.left,
            boundsF.top,
            boundsF.right,
            boundsF.bottom,
            EDGE_COLORS,
            EDGE_STOPS,
            Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = LinearGradient(
            0f,
            boundsF.top,
            0f,
            boundsF.top + boundsF.height() * SHEEN_HEIGHT,
            SHEEN_TOP,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private fun fadeBackground() {
        val bg = background ?: return
        // Skip unchanged values: some drawables (e.g. NinePatchDrawable) invalidate on every
        // setAlpha. ColorDrawable reports its combined alpha here, so a translucent color always
        // differs, but its setAlpha ignores unchanged values itself.
        bg.background?.mutate()?.let { if (it.alpha != backgroundAlpha) it.alpha = backgroundAlpha }
        // A background declared as an ImageView may draw its card through android:src
        if (bg is ImageView) bg.imageAlpha = backgroundAlpha
    }

    // Matches the clip: the host's enforced radius, else the widget's own outline
    private fun cornerRadiusOf(bg: View): Float {
        if (host.hasEnforcedCornerRadius()) return host.enforcedCornerRadius
        if (!bg.clipToOutline) return 0f
        val outline = Outline()
        bg.outlineProvider?.getOutline(bg, outline) ?: return 0f
        return outline.radius.coerceAtLeast(0f)
    }

    private fun blurDrawable(): BackgroundBlurDrawable? {
        if (blurRadius == 0) return null
        blur?.let { return it }
        return try {
            host.viewRootImpl?.createBackgroundBlurDrawable()?.also {
                it.setBlurRadius(blurRadius)
                it.setCornerRadius(cornerRadius)
                blur = it
            }
        } catch (e: LinkageError) {
            // Hidden API: missing or blocked on this device
            Log.w(TAG, "Background blur unavailable", e)
            blurRadius = 0
            null
        }
    }

    companion object {
        private const val TAG = "WidgetBackgroundEffects"
        private const val MAX_BLUR_DP = 40f
        private const val EDGE_WIDTH_DP = 1.2f
        private const val SHEEN_HEIGHT = 0.6f
        private const val SHEEN_TOP = 0x1FFFFFFF
        private val EDGE_COLORS = intArrayOf(0x99FFFFFF.toInt(), 0x22FFFFFF, 0x08FFFFFF, 0x22FFFFFF, 0x66FFFFFF)
        private val EDGE_STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /** Whether the platform can blur behind widgets right now. */
        @JvmStatic
        fun isBlurSupported(view: View): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true

        /**
         * Attaches the effects to [host] when any widget background setting is active.
         * Returns null otherwise.
         */
        @JvmStatic
        fun install(host: BaseLauncherAppWidgetHostView): WidgetBackgroundEffects? {
            val prefs = PreferenceManager2.getInstance(host.context)
            val opacity = prefs.widgetBackgroundOpacity.firstCached(prefs).coerceIn(0f, 1f)
            val alpha = (255 * opacity).roundToInt()
            val blur = prefs.widgetBackgroundBlur.firstCached(prefs).coerceIn(0f, 1f)
            val blurRadius = if (blur > 0f && isBlurSupported(host)) {
                (MAX_BLUR_DP * host.resources.displayMetrics.density * blur).roundToInt()
            } else {
                0
            }
            val glassEdge = prefs.widgetGlassEdge.firstCached(prefs)
            if (alpha == 255 && blurRadius == 0 && !glassEdge) return null
            return WidgetBackgroundEffects(host, alpha, blurRadius, glassEdge).also {
                host.addOnAttachStateChangeListener(it)
            }
        }

        /** Returns the only view with `@android:id/background`, or null if there is none or several. */
        private fun findDeclaredBackground(root: View): View? {
            var found: View? = null
            fun visit(view: View): Boolean {
                if (view.id == android.R.id.background) {
                    if (found != null) return false
                    found = view
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        if (!visit(view.getChildAt(i))) return false
                    }
                }
                return true
            }
            return if (visit(root)) found else null
        }
    }
}
