package com.editoria.app

import android.content.Context

/** Ajustes sencillos guardados en el teléfono. */
object Prefs {
    // Modelo LaMa en formato ONNX (borrar objetos). Si esta URL cambia, se puede editar en Ajustes.
    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"

    private fun sp(c: Context) = c.getSharedPreferences("editoria", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api_key", v.trim()).apply()

    fun modelUrl(c: Context): String = sp(c).getString("model_url", DEFAULT_MODEL_URL) ?: DEFAULT_MODEL_URL
    fun setModelUrl(c: Context, v: String) =
        sp(c).edit().putString("model_url", v.trim().ifBlank { DEFAULT_MODEL_URL }).apply()
}
