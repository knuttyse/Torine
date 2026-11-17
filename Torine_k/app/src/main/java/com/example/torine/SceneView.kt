package no.fibula.torine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class SceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Sprite(
        val bitmap: Bitmap,
        val xImage: Float,
        val yImage: Float,
        val alphaDeg: Float,
        val visible: Boolean
    )

    var spriteScale: Float = 10.0f
        set(value) { field = value; invalidate() }

    private var background: Bitmap? = null
    private val sprites = mutableListOf<Sprite>()
    private val backgroundDst = RectF()
    private val matrix = Matrix()

    init {
        background = BitmapFactory.decodeResource(resources, R.drawable.map4)
    }

    val backgroundWidth: Int get() = background?.width ?: 1
    val backgroundHeight: Int get() = background?.height ?: 1

    fun setSprites(list: List<Sprite>) {
        sprites.clear()
        sprites.addAll(list)
        invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bmp = background
        if (bmp == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }

        val w = MeasureSpec.getSize(widthMeasureSpec)

        // Ideal height driven by width, preserving aspect ratio.
        val idealH = (w.toFloat() * bmp.height / bmp.width).toInt()

        // Cap to a fraction of the available vertical space so the rest of the
        // screen (buttons + status list) is never pushed off-screen on short phones.
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val maxH = (availH * MAX_HEIGHT_FRACTION).toInt()

        val finalH = if (availH > 0) minOf(idealH, maxH) else idealH
        setMeasuredDimension(w, finalH)
    }

    private companion object {
        /** The map will never take more than this fraction of the available height. */
        private const val MAX_HEIGHT_FRACTION = 1.0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = background ?: return
        val vw = width.toFloat()
        val scale = vw / bmp.width.toFloat()
        val drawH = bmp.height * scale
        backgroundDst.set(0f, 0f, vw, drawH)
        canvas.drawBitmap(bmp, null, backgroundDst, null)

        for (s in sprites) {
            if (!s.visible) continue
            val cx = s.xImage * scale
            val cy = s.yImage * scale
            val sw = s.bitmap.width  * spriteScale * scale
            val sh = s.bitmap.height * spriteScale * scale

            matrix.reset()
            matrix.postTranslate(-s.bitmap.width / 2f, -s.bitmap.height / 2f)
            matrix.postScale(sw / s.bitmap.width, sh / s.bitmap.height)
            matrix.postRotate(s.alphaDeg)
            matrix.postTranslate(cx, cy)
            canvas.drawBitmap(s.bitmap, matrix, null)
        }
    }
}
