package com.editoria.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Borrar objetos.
 * - Con el modelo LaMa (ONNX) instalado: relleno con IA, todo en el teléfono.
 * - Sin el modelo: relleno básico por difusión (funciona, pero con menos calidad).
 */
class Inpainter(private val context: Context) {

    private val modelFile get() = File(context.filesDir, "lama.onnx")
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    var lastError: String? = null
        private set

    val hasModel: Boolean get() = modelFile.exists() && modelFile.length() > 1_000_000

    fun download(url: String, onProgress: (Int) -> Unit) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val tmp = File(context.filesDir, "lama.onnx.part")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Respuesta vacía")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        }
        modelFile.delete()
        if (!tmp.renameTo(modelFile)) throw IOException("No se pudo guardar el modelo")
        session?.close()
        session = null
    }

    private fun ensureSession(): OrtSession {
        session?.let { return it }
        val e = OrtEnvironment.getEnvironment()
        env = e
        val s = e.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        session = s
        return s
    }

    /** mask: mismo tamaño que src; los píxeles con alpha > 0 son la zona a borrar. */
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        lastError = null
        val w = src.width
        val h = src.height
        val mpx = IntArray(w * h)
        mask.getPixels(mpx, 0, w, 0, 0, w, h)

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((mpx[y * w + x] ushr 24) > 40) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src

        // Región de trabajo: la zona pintada más contexto alrededor.
        val margin = max(max(maxX - minX, maxY - minY) / 2, 48)
        val l = max(0, minX - margin)
        val t = max(0, minY - margin)
        val r = min(w, maxX + 1 + margin)
        val b = min(h, maxY + 1 + margin)
        val cw = r - l
        val ch = b - t

        var m = BooleanArray(cw * ch)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                m[y * cw + x] = (mpx[(y + t) * w + (x + l)] ushr 24) > 40
            }
        }
        m = dilate(m, cw, ch, 3)

        val crop = Bitmap.createBitmap(src, l, t, cw, ch)
        val filled: Bitmap = if (hasModel) {
            try {
                runLama(crop, m, cw, ch)
            } catch (e: Throwable) {
                lastError = "IA no disponible (${e.message}); se usó el modo básico"
                diffuseFill(crop, m)
            }
        } else {
            diffuseFill(crop, m)
        }

        val cpx = IntArray(cw * ch)
        crop.getPixels(cpx, 0, cw, 0, 0, cw, ch)
        val fpx = IntArray(cw * ch)
        filled.getPixels(fpx, 0, cw, 0, 0, cw, ch)
        for (i in cpx.indices) if (m[i]) cpx[i] = fpx[i]

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(cpx, 0, cw, l, t, cw, ch)
        return out
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var dx = -r
                while (dx <= r && !v) {
                    val nx = x + dx
                    if (nx in 0 until w && m[y * w + nx]) v = true
                    dx++
                }
                tmp[y * w + x] = v
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var dy = -r
                while (dy <= r && !v) {
                    val ny = y + dy
                    if (ny in 0 until h && tmp[ny * w + x]) v = true
                    dy++
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    private fun runLama(crop: Bitmap, mask: BooleanArray, cw: Int, ch: Int): Bitmap {
        val size = 512
        val scaled = Bitmap.createScaledBitmap(crop, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)

        val plane = size * size
        val img = FloatArray(3 * plane)
        val msk = FloatArray(plane)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val p = px[i]
                img[i] = ((p shr 16) and 255) / 255f
                img[plane + i] = ((p shr 8) and 255) / 255f
                img[2 * plane + i] = (p and 255) / 255f
                val sx = x * cw / size
                val sy = y * ch / size
                msk[i] = if (mask[sy * cw + sx]) 1f else 0f
            }
        }

        val s = ensureSession()
        val e = env!!
        val names = s.inputNames.toList()
        val imgName = names.firstOrNull { it.contains("image", true) } ?: names[0]
        val maskName = names.firstOrNull { it.contains("mask", true) } ?: names[1]

        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        val shapeMask = longArrayOf(1, 1, size.toLong(), size.toLong())
        val result = FloatArray(3 * plane)
        OnnxTensor.createTensor(e, FloatBuffer.wrap(img), shape).use { ti ->
            OnnxTensor.createTensor(e, FloatBuffer.wrap(msk), shapeMask).use { tm ->
                s.run(mapOf(imgName to ti, maskName to tm)).use { res ->
                    val fb = (res[0] as OnnxTensor).floatBuffer
                    if (fb.remaining() < result.size) throw IOException("Salida inesperada del modelo")
                    fb.get(result, 0, result.size)
                }
            }
        }

        var maxV = 0f
        for (v in result) if (v > maxV) maxV = v
        val k = if (maxV <= 2f) 255f else 1f

        val outPx = IntArray(plane)
        for (i in 0 until plane) {
            val rr = (result[i] * k).toInt().coerceIn(0, 255)
            val gg = (result[plane + i] * k).toInt().coerceIn(0, 255)
            val bb = (result[2 * plane + i] * k).toInt().coerceIn(0, 255)
            outPx[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        val small = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        small.setPixels(outPx, 0, size, 0, 0, size, size)
        return Bitmap.createScaledBitmap(small, cw, ch, true)
    }

    /** Relleno básico: va "pelando" la zona desde el borde hacia dentro y luego suaviza. */
    private fun diffuseFill(crop: Bitmap, mask: BooleanArray): Bitmap {
        val w = crop.width
        val h = crop.height
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        val known = BooleanArray(w * h) { !mask[it] }

        val idxBuf = IntArray(w * h)
        val colBuf = IntArray(w * h)
        while (true) {
            var count = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (known[i]) continue
                    var n = 0; var sr = 0; var sg = 0; var sb = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                            val j = ny * w + nx
                            if (known[j]) {
                                val p = px[j]
                                sr += (p shr 16) and 255
                                sg += (p shr 8) and 255
                                sb += p and 255
                                n++
                            }
                        }
                    }
                    if (n > 0) {
                        idxBuf[count] = i
                        colBuf[count] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                        count++
                    }
                }
            }
            if (count == 0) break
            for (k in 0 until count) {
                px[idxBuf[k]] = colBuf[k]
                known[idxBuf[k]] = true
            }
        }

        // Suavizado de la zona rellenada
        repeat(20) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (!mask[i]) continue
                    val a = px[i - 1]; val b = px[i + 1]; val c = px[i - w]; val d = px[i + w]
                    val rr = (((a shr 16) and 255) + ((b shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255)) / 4
                    val gg = (((a shr 8) and 255) + ((b shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255)) / 4
                    val bb = ((a and 255) + (b and 255) + (c and 255) + (d and 255)) / 4
                    px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
