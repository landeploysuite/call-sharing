package com.callshare.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * DraggableAodCanvasView
 *
 * Production custom canvas container for CallShare Universal AOD.
 * Provides:
 * - Freeform 2D pointer drag-and-drop for all AOD components (clock, telemetry, custom stickers).
 * - Normalized (0.0 to 1.0) coordinate persistence across Portrait and Landscape profiles via SharedPreferences.
 * - Safe-zone boundary clamping preventing Lissajous burn-in shifts (±10px) from pushing widgets off-screen.
 * - Alignment snap-grid overlay with visual guide lines rendered during drag-and-drop edit mode.
 * - Hardware-accelerated Lissajous translation forwarding to prevent OLED burn-in.
 */
class DraggableAodCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "DraggableAodCanvas"
        private const val PREFS_NAME = "callshare_aod_canvas_prefs"

        // Clamping bounds for Lissajous orbit per orientation (in dp)
        const val LISSAJOUS_MAX_X_PORTRAIT_DP = 6f
        const val LISSAJOUS_MAX_Y_PORTRAIT_DP = 8f
        const val LISSAJOUS_MAX_X_LANDSCAPE_DP = 10f
        const val LISSAJOUS_MAX_Y_LANDSCAPE_DP = 5f

        const val DEFAULT_BEZEL_PADDING_DP = 12f
        const val DEFAULT_GRID_STEP_DP = 16f
    }

    data class AssetItem(
        val viewId: Int,
        val tag: String,
        var normX: Float,
        var normY: Float,
        var scale: Float = 1.0f,
        var zIndex: Float = 1.0f
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val density: Float = resources.displayMetrics.density

    // Canvas Configuration & States
    var isEditMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var isSnapGridEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var gridStepDp: Float = DEFAULT_GRID_STEP_DP

    // Active Dragging State
    private var activeDraggedView: View? = null
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var viewInitialX = 0f
    private var viewInitialY = 0f
    private var isDragging = false

    // Registered AOD widgets
    private val assetItems = mutableMapOf<String, AssetItem>()

    // Visual Guides Paint
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#152238")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val safeZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }

    private val safeZoneRect = RectF()

    init {
        // Ensure onDraw() is called for drawing grid and safe zones
        setWillNotDraw(false)
    }

    /**
     * Determines whether current device configuration is Landscape.
     */
    fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Registers a child view as a draggable asset on the AOD canvas.
     */
    fun registerAsset(view: View, assetTag: String, defaultNormX: Float, defaultNormY: Float) {
        val item = AssetItem(
            viewId = view.id,
            tag = assetTag,
            normX = defaultNormX,
            normY = defaultNormY
        )
        assetItems[assetTag] = item
        loadPersistedPosition(item)
    }

    /**
     * Updates an asset's position and persists to SharedPreferences.
     */
    fun updateAssetCoordinates(assetTag: String, normX: Float, normY: Float, scale: Float = 1.0f) {
        val item = assetItems[assetTag] ?: return
        item.normX = normX.coerceIn(0.0f, 1.0f)
        item.normY = normY.coerceIn(0.0f, 1.0f)
        item.scale = scale
        persistPosition(item)
        applyPositionToView(item)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || width > 0) {
            updateSafeZoneBounds()
            applyAllPersistedPositions()
        }
    }

    private fun updateSafeZoneBounds() {
        val landscape = isLandscape()
        val lissajousX = (if (landscape) LISSAJOUS_MAX_X_LANDSCAPE_DP else LISSAJOUS_MAX_X_PORTRAIT_DP) * density
        val lissajousY = (if (landscape) LISSAJOUS_MAX_Y_LANDSCAPE_DP else LISSAJOUS_MAX_Y_PORTRAIT_DP) * density
        val bezelPadding = DEFAULT_BEZEL_PADDING_DP * density

        val marginX = lissajousX + bezelPadding
        val marginY = lissajousY + bezelPadding

        safeZoneRect.set(
            marginX,
            marginY,
            width - marginX,
            height - marginY
        )
    }

    private fun applyAllPersistedPositions() {
        if (width <= 0 || height <= 0) return
        for ((_, item) in assetItems) {
            loadPersistedPosition(item)
            applyPositionToView(item)
        }
    }

    private fun applyPositionToView(item: AssetItem) {
        val child = findViewById<View>(item.viewId) ?: return
        val targetX = item.normX * (width - child.width)
        val targetY = item.normY * (height - child.height)

        val clamped = clampToSafeZone(targetX, targetY, child.width.toFloat(), child.height.toFloat())
        child.x = clamped.first
        child.y = clamped.second
        child.scaleX = item.scale
        child.scaleY = item.scale
        child.z = item.zIndex
    }

    private fun clampToSafeZone(x: Float, y: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float> {
        val minX = safeZoneRect.left
        val maxX = (safeZoneRect.right - viewWidth).coerceAtLeast(minX)
        val minY = safeZoneRect.top
        val maxY = (safeZoneRect.bottom - viewHeight).coerceAtLeast(minY)

        val clampedX = x.coerceIn(minX, maxX)
        val clampedY = y.coerceIn(minY, maxY)
        return Pair(clampedX, clampedY)
    }

    private fun snapCoordinate(value: Float, stepPx: Float): Float {
        if (!isSnapGridEnabled || stepPx <= 0f) return value
        return (value / stepPx).roundToInt() * stepPx
    }

    // -------------------------------------------------------------------------
    // TOUCH EVENT HANDLING FOR 2D DRAG-AND-DROP
    // -------------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEditMode) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitView = findChildUnder(ev.x, ev.y)
                if (hitView != null) {
                    activeDraggedView = hitView
                    touchDownRawX = ev.rawX
                    touchDownRawY = ev.rawY
                    viewInitialX = hitView.x
                    viewInitialY = hitView.y
                    isDragging = false
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode || activeDraggedView == null) {
            return super.onTouchEvent(event)
        }

        val child = activeDraggedView ?: return super.onTouchEvent(event)
        val stepPx = gridStepDp * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - touchDownRawX
                val deltaY = event.rawY - touchDownRawY

                if (!isDragging && (kotlin.math.abs(deltaX) > 6f || kotlin.math.abs(deltaY) > 6f)) {
                    isDragging = true
                    child.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                    invalidate()
                }

                if (isDragging) {
                    var newX = viewInitialX + deltaX
                    var newY = viewInitialY + deltaY

                    if (isSnapGridEnabled) {
                        newX = snapCoordinate(newX, stepPx)
                        newY = snapCoordinate(newY, stepPx)
                    }

                    val clamped = clampToSafeZone(newX, newY, child.width.toFloat(), child.height.toFloat())
                    child.x = clamped.first
                    child.y = clamped.second
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    child.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()

                    // Compute normalized coordinates
                    val denomX = (width - child.width).coerceAtLeast(1).toFloat()
                    val denomY = (height - child.height).coerceAtLeast(1).toFloat()
                    val normX = (child.x / denomX).coerceIn(0.0f, 1.0f)
                    val normY = (child.y / denomY).coerceIn(0.0f, 1.0f)

                    val item = findItemForView(child)
                    if (item != null) {
                        item.normX = normX
                        item.normY = normY
                        persistPosition(item)
                        Log.d(TAG, "Persisted asset ${item.tag} at norm ($normX, $normY)")
                    }
                    invalidate()
                }
                activeDraggedView = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findChildUnder(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                if (x >= child.x && x <= child.x + child.width &&
                    y >= child.y && y <= child.y + child.height
                ) {
                    return child
                }
            }
        }
        return null
    }

    private fun findItemForView(view: View): AssetItem? {
        return assetItems.values.firstOrNull { it.viewId == view.id }
    }

    // -------------------------------------------------------------------------
    // PERSISTENCE (Separate Portrait & Landscape Profiles)
    // -------------------------------------------------------------------------

    private fun getProfilePrefix(): String {
        return if (isLandscape()) "pos_landscape_" else "pos_portrait_"
    }

    private fun persistPosition(item: AssetItem) {
        val key = getProfilePrefix() + item.tag
        val value = "${item.normX},${item.normY},${item.scale},${item.zIndex}"
        prefs.edit().putString(key, value).apply()
    }

    private fun loadPersistedPosition(item: AssetItem) {
        val key = getProfilePrefix() + item.tag
        val value = prefs.getString(key, null)
        if (value != null) {
            try {
                val parts = value.split(",")
                if (parts.size >= 2) {
                    item.normX = parts[0].toFloat()
                    item.normY = parts[1].toFloat()
                }
                if (parts.size >= 3) {
                    item.scale = parts[2].toFloat()
                }
                if (parts.size >= 4) {
                    item.zIndex = parts[3].toFloat()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing saved coordinates for ${item.tag}: ${e.message}")
            }
        }
    }

    /**
     * Resets coordinates of all assets back to their default profile layouts.
     */
    fun resetToDefaults() {
        val editor = prefs.edit()
        val prefix = getProfilePrefix()
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix)) {
                editor.remove(key)
            }
        }
        editor.apply()
        applyAllPersistedPositions()
        invalidate()
    }

    // -------------------------------------------------------------------------
    // LISSAJOUS BURN-IN TRANSLATION (Hardware-Accelerated)
    // -------------------------------------------------------------------------

    /**
     * Translates child elements by the Lissajous orbit offsets computed by Rust core.
     */
    fun applyBurnInOffset(offsetX: Float, offsetY: Float) {
        if (isEditMode) return // Freeze shifting during user canvas editing
        translationX = offsetX
        translationY = offsetY
    }

    // -------------------------------------------------------------------------
    // VISUAL GUIDES & SNAP GRID DRAWING
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isEditMode) return

        val stepPx = gridStepDp * density

        // 1. Draw Snap-Grid lines
        if (isSnapGridEnabled && stepPx > 0f) {
            var x = safeZoneRect.left
            while (x <= safeZoneRect.right) {
                canvas.drawLine(x, safeZoneRect.top, x, safeZoneRect.bottom, gridPaint)
                x += stepPx
            }

            var y = safeZoneRect.top
            while (y <= safeZoneRect.bottom) {
                canvas.drawLine(safeZoneRect.left, y, safeZoneRect.right, y, gridPaint)
                y += stepPx
            }
        }

        // 2. Draw Safe-Zone Boundary Rectangle
        canvas.drawRect(safeZoneRect, safeZonePaint)
    }
}
