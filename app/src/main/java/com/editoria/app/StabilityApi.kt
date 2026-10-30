package com.editoria.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Expansión de imágenes con IA (outpainting) usando la API de Stability AI. */
object StabilityApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private const val URL = "https://api.stability.ai/v2beta/stable-image/edit/outpaint"

    fun outpaint(apiKey: String, src: Bitmap, left: Int, right: Int, up: Int, down: Int, prompt: String): Bitmap {
        // Se reduce la imagen si es muy grande, y las extensiones se reducen en la misma proporción.
        val s = min(1f, 1536f / max(src.width, src.height))
        val img = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true) else src

        fun sc(v: Int) = min(2000, (v * s).toInt())

        val baos = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.PNG, 100, baos)

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "image.png", baos.toByteArray().toRequestBody("image/png".toMediaType()))
            .addFormDataPart("left", sc(left).toString())
            .addFormDataPart("right", sc(right).toString())
            .addFormDataPart("up", sc(up).toString())
            .addFormDataPart("down", sc(down).toString())
            .addFormDataPart("output_format", "png")
        if (prompt.isNotBlank()) builder.addFormDataPart("prompt", prompt)

        val req = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "image/*")
            .post(builder.build())
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val detail = resp.body?.string()?.take(200) ?: ""
                throw IOException("Error ${resp.code}. $detail")
            }
            val bytes = resp.body?.bytes() ?: throw IOException("Respuesta vacía")
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("No se pudo leer la imagen recibida")
        }
    }
}
