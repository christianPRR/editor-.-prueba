package com.editoria.app

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val inpainter = Inpainter(ctx)

    var image by mutableStateOf<Bitmap?>(null)
        private set
    var maskVersion by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableIntStateOf(-1)
        private set
    var message by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
        private set
    var modelReady by mutableStateOf(inpainter.hasModel)
        private set

    /** Máscara de pintura (mismo tamaño que la imagen). Rojo = zona a borrar. */
    var mask: Bitmap? = null
        private set
    private var hasMask = false
    private var transparent = false
    private val undoStack = ArrayDeque<Bitmap>()

    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // ---------- Carga / historial ----------

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            busy = "Abriendo imagen…"
            try {
                val bmp = withContext(Dispatchers.IO) { decode(uri, 2048) }
                undoStack.clear()
                canUndo = false
                transparent = false
                replaceImage(bmp)
            } catch (e: Throwable) {
                message = "No se pudo abrir la imagen: ${e.message}"
            }
            busy = null
        }
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("formato no compatible")

        val orientation = try {
            resolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val s = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (s < 1f) m.postScale(s, s)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return bmp
    }

    private fun replaceImage(b: Bitmap) {
        image = b
        val old = mask
        if (old == null || old.width != b.width || old.height != b.height) {
            mask = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        } else {
            old.eraseColor(Color.TRANSPARENT)
        }
        hasMask = false
        maskVersion++
    }

    private fun commit(newImg: Bitmap) {
        image?.let {
            undoStack.addLast(it)
            while (undoStack.size > 6) undoStack.removeFirst()
        }
        replaceImage(newImg)
        canUndo = undoStack.isNotEmpty()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        replaceImage(prev)
        canUndo = undoStack.isNotEmpty()
    }

    // ---------- Máscara ----------

    fun strokeMask(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        val m = mask ?: return
        brush.strokeWidth = max(2f, width)
        Canvas(m).drawLine(x1, y1, x2, y2, brush)
        hasMask = true
        maskVersion++
    }

    fun clearMask() {
        mask?.eraseColor(Color.TRANSPARENT)
        hasMask = false
        maskVersion++
    }

    // ---------- IA ----------

    fun eraseObject() {
        val src = image ?: return
        val m = mask ?: return
        if (!hasMask) {
            message = "Primero pinta con el dedo sobre el objeto que quieres borrar."
            return
        }
        viewModelScope.launch {
            busy = if (inpainter.hasModel) "Borrando objeto con IA…" else "Borrando objeto (modo básico)…"
            try {
                val out = withContext(Dispatchers.Default) { inpainter.inpaint(src, m) }
                commit(out)
                inpainter.lastError?.let { message = it }
            } catch (e: Throwable) {
                message = "No se pudo borrar: ${e.message}"
            }
            busy = null
        }
    }

    fun removeBackground() {
        val src = image ?: return
        viewModelScope.launch {
            busy = "Quitando fondo…"
            try {
                val options = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
                val segmenter = SubjectSegmentation.getClient(options)
                val result = withContext(Dispatchers.Default) {
                    segmenter.process(InputImage.fromBitmap(src, 0)).await()
                }
                val fg = result.foregroundBitmap ?: throw IllegalStateException("No se detectó ningún sujeto")
                val out = if (fg.width == src.width && fg.height == src.height) fg
                else Bitmap.createScaledBitmap(fg, src.width, src.height, true)
                transparent = true
                commit(out)
            } catch (e: Throwable) {
                message = "No se pudo quitar el fondo: ${e.message}. (La primera vez necesita internet para bajar el modelo.)"
            }
            busy = null
        }
    }

    fun expand(left: Int, right: Int, up: Int, down: Int, prompt: String) {
        val src = image ?: return
        if (left + right + up + down <= 0) {
            message = "Elige al menos un lado para expandir."
            return
        }
        val key = Prefs.apiKey(ctx)
        viewModelScope.launch {
            busy = if (key.isNotBlank()) "Expandiendo con IA… (puede tardar)" else "Expandiendo…"
            try {
                val out = withContext(Dispatchers.IO) {
                    if (key.isNotBlank()) StabilityApi.outpaint(key, src, left, right, up, down, prompt)
                    else ImageOps.expandBlur(src, left, right, up, down)
                }
                commit(out)
                if (key.isBlank()) {
                    message = "Se expandió con relleno difuminado. Para expansión con IA agrega tu clave de Stability AI en Ajustes."
                }
            } catch (e: Throwable) {
                message = "No se pudo expandir: ${e.message}"
            }
            busy = null
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            busy = "Descargando modelo de IA…"
            progress = 0
            try {
                withContext(Dispatchers.IO) {
                    inpainter.download(Prefs.modelUrl(ctx)) { progress = it }
                }
                modelReady = true
                message = "Modelo instalado. Ya puedes borrar objetos con IA."
            } catch (e: Throwable) {
                message = "No se pudo descargar el modelo: ${e.message}"
            }
            progress = -1
            busy = null
        }
    }

    // ---------- Edición básica ----------

    fun rotate() {
        val src = image ?: return
        commit(ImageOps.rotate90(src))
    }

    fun crop(rw: Int, rh: Int) {
        val src = image ?: return
        commit(ImageOps.centerCrop(src, rw, rh))
    }

    fun filter(name: String) {
        val src = image ?: return
        viewModelScope.launch {
            busy = "Aplicando filtro…"
            val out = withContext(Dispatchers.Default) { ImageOps.filter(src, name) }
            commit(out)
            busy = null
        }
    }

    fun applyAdjust(matrix: FloatArray) {
        val src = image ?: return
        viewModelScope.launch {
            busy = "Aplicando ajustes…"
            val out = withContext(Dispatchers.Default) { ImageOps.applyMatrix(src, matrix) }
            commit(out)
            busy = null
        }
    }

    // ---------- Guardar ----------

    fun save() {
        val bmp = image ?: return
        viewModelScope.launch {
            busy = "Guardando…"
            try {
                withContext(Dispatchers.IO) {
                    val png = transparent
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "EditorIA_${System.currentTimeMillis()}." + if (png) "png" else "jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EditorIA")
                        }
                    }
                    val resolver = ctx.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("no se pudo crear el archivo")
                    resolver.openOutputStream(uri)!!.use { out ->
                        if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        else bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
                message = "Guardada en Galería › EditorIA"
            } catch (e: Throwable) {
                message = "No se pudo guardar: ${e.message}"
            }
            busy = null
        }
    }
}
