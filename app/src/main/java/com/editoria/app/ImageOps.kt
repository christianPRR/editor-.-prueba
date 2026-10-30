package com.editoria.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/** Operaciones básicas de imagen (sin IA). */
object ImageOps {

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Recorte centrado con una proporción (ej. 16:9). */
    fun centerCrop(src: Bitmap, rw: Int, rh: Int): Bitmap {
        val target = rw.toFloat() / rh
        val current = src.width.toFloat() / src.height
        var w = src.width
        var h = src.height
        if (current > target) w = (src.height * target).toInt() else h = (src.width / target).toInt()
        w = max(1, min(w, src.width))
        h = max(1, min(h, src.height))
        val x = (src.width - w) / 2
        val y = (src.height - h) / 2
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /** Matriz de color para brillo (-100..100), contraste (0.5..1.5) y saturación (0..2). */
    fun adjustMatrix(brightness: Float, contrast: Float, saturation: Float): FloatArray {
        val cm = ColorMatrix()
        cm.setSaturation(saturation)
        val t = (-0.5f * contrast + 0.5f) * 255f + brightness
        val c = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(c)
        return cm.array
    }

    fun applyMatrix(src: Bitmap, matrix: FloatArray): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun filter(src: Bitmap, name: String): Bitmap {
        val m: FloatArray = when (name) {
            "bn" -> ColorMatrix().apply { setSaturation(0f) }.array
            "sepia" -> floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            "vivido" -> adjustMatrix(0f, 1.1f, 1.5f)
            "frio" -> floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1.1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            "calido" -> floatArrayOf(
                1.1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 0.9f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            else -> return src
        }
        return applyMatrix(src, m)
    }

    /** Expansión sin IA: el fondo nuevo se rellena con una versión muy difuminada de la foto. */
    fun expandBlur(src: Bitmap, l: Int, r: Int, u: Int, d: Int): Bitmap {
        val nw = src.width + l + r
        val nh = src.height + u + d
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val tiny = Bitmap.createScaledBitmap(src, 12, max(1, 12 * src.height / src.width), true)
        canvas.drawBitmap(tiny, null, Rect(0, 0, nw, nh), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawBitmap(src, l.toFloat(), u.toFloat(), null)
        return out
    }
}
