package com.firework.remote3000

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class BuddyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mood { IDLE, HAPPY, LISTENING, SPEAKING, EXCITED }

    private data class Spark(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val color: Int
    )

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#04101F") }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 105, 180) }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#04101F")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sparks = mutableListOf<Spark>()
    private var mood: Mood = Mood.IDLE
    private var animTime = 0f
    private var bounceOffset = 0f
    private var blinkTimer = 0f
    private var isBlinking = false

    var onBuddyTap: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            animTime += 0.06f
            bounceOffset *= 0.85f
            blinkTimer += 0.06f
            if (blinkTimer > 3.6f) {
                isBlinking = true
                if (blinkTimer > 3.78f) {
                    isBlinking = false
                    blinkTimer = 0f
                }
            }
            val it = sparks.iterator()
            while (it.hasNext()) {
                val s = it.next()
                s.x += s.vx
                s.y += s.vy
                s.vy += 0.18f
                s.life -= 0.035f
                if (s.life <= 0f) it.remove()
            }
            invalidate()
            postDelayed(this, 25L)
        }
    }

    init {
        isClickable = true
        post(tick)
    }

    fun setMood(newMood: Mood) {
        mood = newMood
        if (newMood == Mood.EXCITED || newMood == Mood.HAPPY) {
            triggerSparkles()
            bounceOffset = -18f
        }
    }

    fun triggerSparkles() {
        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0f || cy <= 0f) return
        val colors = intArrayOf(
            Color.parseColor("#00F5D4"),
            Color.parseColor("#FFD166"),
            Color.parseColor("#FF2A6D"),
            Color.parseColor("#38BDF8")
        )
        for (i in 0 until 18) {
            val ang = Random.nextFloat() * (Math.PI * 2).toFloat()
            val spd = 2.5f + Random.nextFloat() * 5.5f
            sparks.add(
                Spark(
                    x = cx,
                    y = cy,
                    vx = cos(ang) * spd,
                    vy = sin(ang) * spd - 1.5f,
                    life = 1f,
                    color = colors[i % colors.size]
                )
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            bounceOffset = -22f
            triggerSparkles()
            onBuddyTap?.invoke()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val baseY = height / 2f + sin(animTime * 1.8f) * 8f + bounceOffset
        val r = (minOf(width, height) * 0.30f).coerceAtLeast(36f)

        // Outer neon aura
        val auraColor = when (mood) {
            Mood.LISTENING -> Color.argb(95, 255, 42, 109)
            Mood.SPEAKING -> Color.argb(95, 255, 209, 102)
            Mood.EXCITED -> Color.argb(110, 56, 189, 248)
            else -> Color.argb(80, 0, 245, 212)
        }
        glowPaint.color = auraColor
        val pulse = 1.15f + sin(animTime * 3f) * 0.08f
        canvas.drawCircle(cx, baseY, r * pulse, glowPaint)

        // Antenna & glowing firework star
        mouthPaint.color = Color.parseColor("#38BDF8")
        mouthPaint.strokeWidth = 5f
        canvas.drawLine(cx, baseY - r, cx, baseY - r - 22f, mouthPaint)
        sparkPaint.color = Color.parseColor("#FFD166")
        canvas.drawCircle(cx, baseY - r - 26f, 8f + sin(animTime * 5f) * 2f, sparkPaint)

        // Main rounded cute body
        bodyPaint.color = when (mood) {
            Mood.LISTENING -> Color.parseColor("#38BDF8")
            Mood.EXCITED -> Color.parseColor("#FFD166")
            else -> Color.parseColor("#00F5D4")
        }
        canvas.drawCircle(cx, baseY, r, bodyPaint)

        // Inner glossy highlight
        glowPaint.color = Color.argb(65, 255, 255, 255)
        canvas.drawCircle(cx - r * 0.28f, baseY - r * 0.30f, r * 0.28f, glowPaint)

        // Blush cheeks
        canvas.drawCircle(cx - r * 0.45f, baseY + r * 0.14f, r * 0.14f, blushPaint)
        canvas.drawCircle(cx + r * 0.45f, baseY + r * 0.14f, r * 0.14f, blushPaint)

        // Eyes
        val eyeY = baseY - r * 0.10f
        val eyeDx = r * 0.32f
        val eyeR = r * 0.13f
        if (isBlinking && mood != Mood.LISTENING) {
            mouthPaint.color = Color.parseColor("#04101F")
            mouthPaint.strokeWidth = 5f
            canvas.drawLine(cx - eyeDx - eyeR, eyeY, cx - eyeDx + eyeR, eyeY, mouthPaint)
            canvas.drawLine(cx + eyeDx - eyeR, eyeY, cx + eyeDx + eyeR, eyeY, mouthPaint)
        } else {
            canvas.drawCircle(cx - eyeDx, eyeY, eyeR, eyePaint)
            canvas.drawCircle(cx + eyeDx, eyeY, eyeR, eyePaint)
            canvas.drawCircle(cx - eyeDx - eyeR * 0.25f, eyeY - eyeR * 0.25f, eyeR * 0.35f, highlightPaint)
            canvas.drawCircle(cx + eyeDx - eyeR * 0.25f, eyeY - eyeR * 0.25f, eyeR * 0.35f, highlightPaint)
        }

        // Mouth animation
        mouthPaint.color = Color.parseColor("#04101F")
        mouthPaint.strokeWidth = 5.5f
        val mouthY = baseY + r * 0.22f
        when (mood) {
            Mood.SPEAKING -> {
                val openH = (8f + kotlin.math.abs(sin(animTime * 9f)) * 14f)
                val rect = RectF(cx - r * 0.22f, mouthY - openH / 2f, cx + r * 0.22f, mouthY + openH / 2f)
                eyePaint.color = Color.parseColor("#04101F")
                canvas.drawOval(rect, eyePaint)
            }
            Mood.LISTENING -> {
                canvas.drawCircle(cx, mouthY + 2f, r * 0.11f, mouthPaint)
            }
            else -> {
                val rect = RectF(cx - r * 0.26f, mouthY - r * 0.18f, cx + r * 0.26f, mouthY + r * 0.16f)
                canvas.drawArc(rect, 15f, 150f, false, mouthPaint)
            }
        }

        // Mini firework sparkles
        for (s in sparks) {
            sparkPaint.color = s.color
            sparkPaint.alpha = (s.life * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, 4f + s.life * 4f, sparkPaint)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
