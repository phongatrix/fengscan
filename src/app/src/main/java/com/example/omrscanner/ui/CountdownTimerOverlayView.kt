package com.example.omrscanner.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.omrscanner.camera.FiducialState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * HUD căn chỉnh phiếu + đồng hồ đếm ngược tự chụp.
 *
 * **Khung đỏ = đúng khung ảnh kết quả.** Tỷ lệ khung lấy theo tỷ lệ ảnh mẫu
 * (`TemplateConfig.outputWidth/outputHeight`), và 4 ô mục tiêu vẽ tại đúng vị
 * trí 4 chấm định vị trong ảnh mẫu (`TemplateConfig.fiducialTarget`). Nhờ vậy:
 *  - phần giấy nằm trong khung đỏ chính là phần ảnh được lưu ra (vd `kq12.png`);
 *  - khi 4 chấm lọt vào 4 ô mục tiêu -> khung đỏ chuyển **xanh lá** -> tự chụp 3s.
 *
 * Màu: ĐỎ (chưa đủ chấm) -> VÀNG (thấy một phần) -> XANH LÁ (khớp, glow).
 */
class CountdownTimerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var state: FiducialState? = null

    /** Tỷ lệ w/h của khung ImageAnalysis sau khi xoay về hướng màn hình (4:3 dọc = 0.75). */
    var analysisAspect: Float = 0.75f

    /**
     * Tỷ lệ **w/h của toàn trang phiếu trong ảnh kết quả**
     * (`outputWidth / outputHeight` của mẫu đang chọn).
     */
    var sheetAspect: Float = 0.71f

    /**
     * 4 tâm chấm định vị trong ảnh kết quả, chuẩn hoá 0..1
     * `[TL.x, TL.y, TR.x, TR.y, BR.x, BR.y, BL.x, BL.y]` — lấy từ
     * `TemplateConfig.fiducialTarget` của mẫu đang chọn.
     */
    var fiducialTarget: FloatArray = DEFAULT_TARGET

    /** Mép trên bị panel mẫu giấy chiếm (px) — khung đỏ không được đè lên. */
    var topInset: Int = 0

    /** Mép dưới bị thanh điều khiển chiếm (px). */
    var bottomInset: Int = 0

    // --- màu động ---
    private var currentColor: Int = COLOR_RED
    private var colorAnimator: ValueAnimator? = null
    private var glowPhase: Float = 0f
    private var glowAnimator: ValueAnimator? = null

    // --- đếm ngược auto-capture ---
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownValue = 0
    private var countdownProgress = 0f
    private var countdownSeconds = 3
    private var countdownAnimator: ValueAnimator? = null
    private var digitPulse = 0f
    private var digitPulseAnimator: ValueAnimator? = null
    private val countdownRunnables = ArrayList<Runnable>()

    /** Phát mỗi giây khi chữ số giảm (để bíp/rung nhẹ). */
    var onCountdownTick: ((secondsLeft: Int) -> Unit)? = null

    /** Phát một lần khi đếm về 0 và khung vẫn vững -> chụp. */
    var onCountdownFinished: (() -> Unit)? = null

    val isCountingDown: Boolean get() = countdownValue > 0

    /** Số chấm định vị đang nằm đúng ô mục tiêu của nó (0..4). */
    var matchedCount: Int = 0
        private set

    /** true khi cả 4 chấm đã khớp 4 ô mục tiêu. */
    var isAligned: Boolean = false
        private set

    private var matchedFlags = BooleanArray(4)
    private val mapped = arrayOf(PointF(), PointF(), PointF(), PointF())
    private val targets = arrayOf(PointF(), PointF(), PointF(), PointF())

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = CornerPathEffect(14f)
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OUTSIDE_DIM
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = CornerPathEffect(14f)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val targetTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val progressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = 0x33FFFFFF
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        color = COLOR_GREEN
    }
    private val digitHaloPaint = Paint(digitPaint).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    // ------------------------------------------------------------------

    fun updateState(s: FiducialState) {
        state = s
        evaluateMatch()
        animateColorTo(targetColor())
        updateGlow(isAligned)
        if (!isAligned) cancelCountdown() // safety reset
        invalidate()
    }

    /**
     * Đánh giá 4 chấm phát hiện được so với 4 ô mục tiêu của mẫu.
     *
     * Điều kiện hợp lệ: mỗi chấm phải nằm trong khung đỏ **và** cách tâm ô mục
     * tiêu của nó không quá [MATCH_RADIUS] (tỷ lệ chiều rộng khung) — nhờ vậy
     * ảnh chụp ra đúng khổ, không bị lệch/cắt xén dù người dùng căn tay.
     */
    private fun evaluateMatch() {
        val q = state?.quadNormalized
        if (q == null || width == 0 || height == 0) {
            matchedFlags = BooleanArray(4)
            matchedCount = 0
            isAligned = false
            return
        }
        val guide = guideRect()
        mapQuadToView(q)
        targetPoints(guide)

        val pad = min(guide.width(), guide.height()) * INSIDE_PADDING
        val inside = RectF(guide.left - pad, guide.top - pad, guide.right + pad, guide.bottom + pad)
        val radius = guide.width() * MATCH_RADIUS

        matchedFlags = BooleanArray(4) { i ->
            inside.contains(mapped[i].x, mapped[i].y) && dist(mapped[i], targets[i]) <= radius
        }
        matchedCount = matchedFlags.count { it }

        // Khối 4 chấm phải chiếm phần đáng kể trong khung, tránh chấm nhiễu.
        val qw = (dist(mapped[0], mapped[1]) + dist(mapped[3], mapped[2])) / 2f
        val qh = (dist(mapped[0], mapped[3]) + dist(mapped[1], mapped[2])) / 2f
        val bigEnough = qw >= guide.width() * MIN_QUAD_FRACTION &&
            qh >= guide.height() * MIN_QUAD_FRACTION

        isAligned = matchedCount == 4 && bigEnough
    }

    /** Vị trí 4 ô mục tiêu (px) trong view, suy từ [fiducialTarget]. */
    private fun targetPoints(guide: RectF) {
        for (i in 0..3) {
            targets[i].set(
                guide.left + fiducialTarget[i * 2] * guide.width(),
                guide.top + fiducialTarget[i * 2 + 1] * guide.height(),
            )
        }
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun targetColor(): Int = when {
        isAligned -> COLOR_GREEN
        matchedCount > 0 || state?.quadNormalized != null -> COLOR_YELLOW
        else -> COLOR_RED
    }

    private fun animateColorTo(target: Int) {
        if (target == currentColor) return
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            val from = currentColor
            addUpdateListener {
                currentColor = blend(from, target, it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    private fun updateGlow(active: Boolean) {
        if (active && glowAnimator == null) {
            glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    glowPhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!active && glowAnimator != null) {
            glowAnimator?.cancel()
            glowAnimator = null
            glowPhase = 0f
        }
    }

    // ------------------------------------------------------------------
    // Đếm ngược 3 giây
    // ------------------------------------------------------------------

    fun startCountdown(seconds: Int = 3) {
        cancelCountdown()
        countdownSeconds = seconds.coerceIn(1, 10)
        countdownValue = countdownSeconds
        onCountdownTick?.invoke(countdownValue)
        pulseDigit()

        countdownAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = countdownSeconds * 1000L
            addUpdateListener {
                countdownProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        val remaining = intArrayOf(countdownSeconds)
        repeat(countdownSeconds) {
            val r = Runnable {
                remaining[0] -= 1
                countdownValue = remaining[0]
                if (countdownValue <= 0) {
                    countdownValue = 0
                    onCountdownFinished?.invoke()
                    countdownHandler.postDelayed({
                        countdownProgress = 0f
                        invalidate()
                    }, 500)
                } else {
                    onCountdownTick?.invoke(countdownValue)
                    pulseDigit()
                }
            }
            countdownRunnables += r
            countdownHandler.postDelayed(r, (it + 1) * 1000L)
        }
        invalidate()
    }

    /** Hủy đếm ngược (safety reset khi khung bị lệch). */
    fun cancelCountdown() {
        countdownRunnables.forEach { countdownHandler.removeCallbacks(it) }
        countdownRunnables.clear()
        countdownAnimator?.cancel()
        countdownAnimator = null
        countdownValue = 0
        countdownProgress = 0f
        invalidate()
    }

    private fun pulseDigit() {
        digitPulseAnimator?.cancel()
        digitPulseAnimator = ValueAnimator.ofFloat(0.35f, 0f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                digitPulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val guide = guideRect()
        targetPoints(guide)

        // 1) Làm tối phần NGOÀI khung đỏ — phần sáng = đúng phần giấy sẽ lưu.
        drawOutsideDim(canvas, guide)

        // 2) Khung đỏ (viền mảnh) + 4 ô mục tiêu
        framePaint.color = withAlpha(currentColor, 0xAA)
        framePaint.strokeWidth = 2f
        canvas.drawRect(guide, framePaint)
        drawTargetBoxes(canvas, guide)

        // 3) Tứ giác 4 chấm phát hiện được
        state?.quadNormalized?.let { q ->
            mapQuadToView(q)
            if (isAligned) {
                val glow = 0.35f + 0.65f * glowPhase
                for (i in 3 downTo 1) {
                    glowPaint.color = withAlpha(COLOR_GREEN, ((60 / i) * glow).toInt())
                    glowPaint.strokeWidth = 5f + i * 7f
                    canvas.drawPath(quadPath(), glowPaint)
                }
            }
            framePaint.color = currentColor
            framePaint.strokeWidth = 6f
            canvas.drawPath(quadPath(), framePaint)

            for (i in 0..3) {
                val p = mapped[i]
                dotPaint.color = if (matchedFlags[i]) COLOR_GREEN else currentColor
                canvas.drawCircle(p.x, p.y, if (matchedFlags[i]) 7f else 5f, dotPaint)
            }
        }

        // 4) Chữ số đếm ngược to giữa khung
        if (countdownValue > 0) {
            val textSize = min(guide.width(), guide.height()) * 0.26f * (1f + digitPulse)
            digitPaint.textSize = textSize
            digitHaloPaint.textSize = textSize
            digitHaloPaint.strokeWidth = textSize / 12f
            val cx = guide.centerX()
            val cy = guide.centerY() + textSize * 0.35f
            canvas.drawText(countdownValue.toString(), cx, cy, digitHaloPaint)
            canvas.drawText(countdownValue.toString(), cx, cy, digitPaint)
        }

        // 5) Thanh tiến trình đếm ngược
        if (countdownProgress > 0f) {
            val barTop = min(guide.bottom + 14f, height - bottomInset - 14f)
            val barRect = RectF(guide.left, barTop, guide.right, barTop + 8f)
            canvas.drawRoundRect(barRect, 4f, 4f, progressBgPaint)
            val fill = RectF(
                guide.left, barTop,
                guide.left + (guide.right - guide.left) * countdownProgress,
                barTop + 8f,
            )
            progressPaint.color = COLOR_GREEN
            canvas.drawRoundRect(fill, 4f, 4f, progressPaint)
        }
    }

    /** Vẽ 4 ô vuông mục tiêu tại đúng vị trí 4 chấm của mẫu (kèm tâm). */
    private fun drawTargetBoxes(canvas: Canvas, guide: RectF) {
        val half = guide.width() * TARGET_BOX_FRACTION
        for (i in 0..3) {
            val t = targets[i]
            val matched = matchedFlags[i]
            val color = if (matched) COLOR_GREEN else currentColor
            targetPaint.color = color
            targetPaint.strokeWidth = if (matched) 4.5f else 3f
            canvas.drawRect(t.x - half, t.y - half, t.x + half, t.y + half, targetPaint)

            // 4 vạch ngắn ở tâm ô: tâm chấm phải trùng tâm ô
            targetTickPaint.color = withAlpha(color, 0xCC)
            targetTickPaint.strokeWidth = 2.5f
            val tick = half * 0.55f
            canvas.drawLine(t.x - tick, t.y, t.x + tick, t.y, targetTickPaint)
            canvas.drawLine(t.x, t.y - tick, t.x, t.y + tick, targetTickPaint)
        }
    }

    /** Tối 4 dải quanh khung đỏ: vùng sáng là đúng phần ảnh sẽ được lưu. */
    private fun drawOutsideDim(canvas: Canvas, guide: RectF) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, guide.top, dimPaint)
        canvas.drawRect(0f, guide.bottom, w, h, dimPaint)
        canvas.drawRect(0f, guide.top, guide.left, guide.bottom, dimPaint)
        canvas.drawRect(guide.right, guide.top, w, guide.bottom, dimPaint)
    }

    /**
     * Khung đỏ = khung ảnh kết quả: vừa với vùng trống giữa panel mẫu giấy
     * ([topInset]) và thanh điều khiển ([bottomInset]), tỷ lệ theo
     * [sheetAspect] (w/h của toàn trang trong ảnh ra). Lấy GIẤY tối đa để
     * người dùng cầm máy càng thấp/cách phiếu càng gần càng tốt.
     */
    private fun guideRect(): RectF {
        val gap = (height * 0.008f).coerceIn(3f, 12f)
        val availTop = topInset + gap
        val availBottom = height - bottomInset - gap
        val availH = (availBottom - availTop).coerceAtLeast(height * 0.2f)
        val availW = width * GUIDE_MAX_WIDTH_FRACTION

        var gh = availH
        var gw = gh * sheetAspect
        if (gw > availW) {
            gw = availW
            gh = gw / sheetAspect
        }
        val left = (width - gw) / 2f
        val top = availTop + (availH - gh) / 2f
        return RectF(left, top, left + gw, top + gh)
    }

    private fun mapQuadToView(q: FloatArray) {
        for (i in 0..3) mapNormalizedPoint(q[i * 2], q[i * 2 + 1], mapped[i])
    }

    /** Map 1 điểm normalized (khung phân tích) ra tọa độ view — cơ chế FILL_CENTER. */
    fun mapNormalizedPoint(nx: Float, ny: Float, out: PointF) {
        val aw = analysisAspect
        val ah = 1f
        val scale = max(width / aw, height / ah)
        out.set(
            (width - aw * scale) / 2f + nx * aw * scale,
            (height - ah * scale) / 2f + ny * ah * scale,
        )
    }

    /** Nghịch đảo của [mapNormalizedPoint]: tọa độ view -> normalized 0..1. */
    private fun toNormalized(x: Float, y: Float, out: FloatArray, offset: Int) {
        val aw = analysisAspect
        val ah = 1f
        val scale = max(width / aw, height / ah)
        val dx = (width - aw * scale) / 2f
        val dy = (height - ah * scale) / 2f
        out[offset] = ((x - dx) / scale / aw).coerceIn(0f, 1f)
        out[offset + 1] = ((y - dy) / scale / ah).coerceIn(0f, 1f)
    }

    /**
     * Tọa độ 4 chấm **giả định** khi bộ dò không tìm thấy chấm: người dùng đã
     * đặt 4 chấm vào 4 ô mục tiêu, nên lấy chính tâm 4 ô đó (KHÔNG lấy 4 góc
     * khung — khung là toàn trang, 4 góc khung không phải vị trí chấm).
     */
    fun fallbackQuadNormalized(): FloatArray {
        val guide = guideRect()
        targetPoints(guide)
        val out = FloatArray(8)
        for (i in 0..3) toNormalized(targets[i].x, targets[i].y, out, i * 2)
        return out
    }

    private fun quadPath(): Path {
        val p = Path()
        p.reset()
        p.moveTo(mapped[0].x, mapped[0].y)
        for (i in 1..3) p.lineTo(mapped[i].x, mapped[i].y)
        p.close()
        return p
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelCountdown()
    }

    // ------------------------------------------------------------------

    companion object {
        private const val COLOR_RED = 0xFFE53935.toInt()
        private const val COLOR_YELLOW = 0xFFFFC107.toInt()
        private const val COLOR_GREEN = 0xFF00E676.toInt()

        /** Làm tối phần ngoài khung (0x00..0xFF alpha). */
        private const val OUTSIDE_DIM = 0x66000000

        /** Nới khung khi xét "chấm nằm trong khung" (tỷ lệ cạnh ngắn khung). */
        private const val INSIDE_PADDING = 0.03f

        /** Nửa cạnh ô mục tiêu vẽ trên màn hình (tỷ lệ chiều rộng khung). */
        private const val TARGET_BOX_FRACTION = 0.055f

        /** Bán kính khớp: chấm phải nằm trong khoảng này quanh tâm ô mục tiêu. */
        private const val MATCH_RADIUS = 0.075f

        /** Khối 4 chấm phải chiếm ít nhất tỷ lệ này của khung để coi là phiếu thật. */
        private const val MIN_QUAD_FRACTION = 0.40f

        /** Chiều rộng tối đa khung đỏ = 97% chiều rộng view (khung càng to,
         *  camera cầm càng thấp/cách phiếu càng gần). */
        private const val GUIDE_MAX_WIDTH_FRACTION = 0.97f

        /** Vị trí 4 chấm mặc định (mẫu 12) — sẽ được ghi đè từ TemplateConfig. */
        private val DEFAULT_TARGET = floatArrayOf(
            0.04119f, 0.03886f,
            0.94873f, 0.03599f,
            0.93789f, 0.94308f,
            0.06353f, 0.95204f,
        )

        fun withAlpha(color: Int, alpha: Int): Int =
            (min(alpha, 255) shl 24) or (color and 0x00FFFFFF)

        fun blend(from: Int, to: Int, t: Float): Int {
            val fr = Color.red(from).toFloat()
            val fg = Color.green(from).toFloat()
            val fb = Color.blue(from).toFloat()
            val tr = Color.red(to).toFloat()
            val tg = Color.green(to).toFloat()
            val tb = Color.blue(to).toFloat()
            return Color.rgb(
                (fr + (tr - fr) * t).toInt(),
                (fg + (tg - fg) * t).toInt(),
                (fb + (tb - fb) * t).toInt(),
            )
        }
    }
}
