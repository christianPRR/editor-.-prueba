package com.editoria.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    App()
                }
            }
        }
    }
}

enum class Mode { NONE, ERASE, EXPAND, ADJUST, FILTERS, CROP }

@Composable
fun App(vm: EditorViewModel = viewModel()) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var mode by remember { mutableStateOf(Mode.NONE) }
    var showSettings by remember { mutableStateOf(false) }

    var brush by remember { mutableFloatStateOf(48f) }
    var bright by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var sat by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(vm.message) {
        val msg = vm.message
        if (msg != null) {
            snack.showSnackbar(msg)
            vm.message = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            vm.loadImage(uri)
            mode = Mode.NONE
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.save() else vm.message = "Sin permiso para guardar en la galería."
    }

    fun pickImage() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun requestSave() {
        if (Build.VERSION.SDK_INT < 29 &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.save()
        }
    }

    val adjustMatrix = ImageOps.adjustMatrix(bright, contrast, sat)

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = Color(0xFF121212)
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {

                // Barra superior
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Editor IA", fontSize = 20.sp, color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickImage() }) { Text("Abrir") }
                    TextButton(onClick = { vm.undo() }, enabled = vm.canUndo) { Text("Deshacer") }
                    TextButton(onClick = { requestSave() }, enabled = vm.image != null) { Text("Guardar") }
                    TextButton(onClick = { showSettings = true }) { Text("⚙") }
                }

                // Imagen
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (vm.image == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Elige una foto para empezar", color = Color.LightGray)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { pickImage() }) { Text("Elegir imagen") }
                        }
                    } else {
                        ImageCanvas(
                            vm = vm,
                            paintMode = mode == Mode.ERASE,
                            brushPx = brush,
                            adjust = if (mode == Mode.ADJUST) adjustMatrix else null
                        )
                    }
                }

                // Panel de la herramienta activa
                val img = vm.image
                if (img != null) {
                    val panel = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(12.dp)
                    when (mode) {
                        Mode.ERASE -> Column(panel) {
                            if (!vm.modelReady) {
                                Text(
                                    "Modo básico activo. Descarga el modelo de IA (una sola vez, ~200 MB) para borrar con mucha mejor calidad.",
                                    color = Color(0xFFFFCC80), fontSize = 12.sp
                                )
                                Button(onClick = { vm.downloadModel() }) { Text("Descargar modelo de IA") }
                            }
                            Text("Pinta con el dedo sobre el objeto y pulsa Borrar.", color = Color.LightGray, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Pincel", color = Color.White, fontSize = 13.sp)
                                Slider(value = brush, onValueChange = { brush = it }, valueRange = 16f..120f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.clearMask() }) { Text("Limpiar") }
                                Button(onClick = { vm.eraseObject() }) { Text("✨ Borrar objeto") }
                            }
                        }

                        Mode.EXPAND -> ExpandPanel(panel, img.width, img.height, vm)

                        Mode.ADJUST -> Column(panel) {
                            LabeledSlider("Brillo", bright, -100f..100f) { bright = it }
                            LabeledSlider("Contraste", contrast, 0.5f..1.5f) { contrast = it }
                            LabeledSlider("Saturación", sat, 0f..2f) { sat = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { bright = 0f; contrast = 1f; sat = 1f }) { Text("Restablecer") }
                                Button(onClick = {
                                    vm.applyAdjust(adjustMatrix)
                                    bright = 0f; contrast = 1f; sat = 1f
                                }) { Text("Aplicar") }
                            }
                        }

                        Mode.FILTERS -> Row(panel.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.filter("bn") }) { Text("B/N") }
                            OutlinedButton(onClick = { vm.filter("sepia") }) { Text("Sepia") }
                            OutlinedButton(onClick = { vm.filter("vivido") }) { Text("Vívido") }
                            OutlinedButton(onClick = { vm.filter("frio") }) { Text("Frío") }
                            OutlinedButton(onClick = { vm.filter("calido") }) { Text("Cálido") }
                        }

                        Mode.CROP -> Row(panel.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.crop(1, 1) }) { Text("1:1") }
                            OutlinedButton(onClick = { vm.crop(4, 5) }) { Text("4:5") }
                            OutlinedButton(onClick = { vm.crop(3, 4) }) { Text("3:4") }
                            OutlinedButton(onClick = { vm.crop(16, 9) }) { Text("16:9") }
                            OutlinedButton(onClick = { vm.crop(9, 16) }) { Text("9:16") }
                        }

                        Mode.NONE -> {}
                    }

                    // Barra de herramientas
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF181818)).horizontalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ToolButton("🧽", "Borrar", mode == Mode.ERASE) { mode = if (mode == Mode.ERASE) Mode.NONE else Mode.ERASE }
                        ToolButton("✂️", "Fondo", false) { vm.removeBackground() }
                        ToolButton("↔️", "Expandir", mode == Mode.EXPAND) { mode = if (mode == Mode.EXPAND) Mode.NONE else Mode.EXPAND }
                        ToolButton("🎚️", "Ajustes", mode == Mode.ADJUST) { mode = if (mode == Mode.ADJUST) Mode.NONE else Mode.ADJUST }
                        ToolButton("🎨", "Filtros", mode == Mode.FILTERS) { mode = if (mode == Mode.FILTERS) Mode.NONE else Mode.FILTERS }
                        ToolButton("📐", "Recortar", mode == Mode.CROP) { mode = if (mode == Mode.CROP) Mode.NONE else Mode.CROP }
                        ToolButton("🔄", "Girar", false) { vm.rotate() }
                    }
                }
            }

            // Pantalla de espera
            val busyText = vm.busy
            if (busyText != null) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (vm.progress in 0..100) {
                            LinearProgressIndicator(progress = { vm.progress / 100f }, modifier = Modifier.width(220.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("${vm.progress}%", color = Color.White)
                        } else {
                            CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(busyText, color = Color.White)
                    }
                }
            }
        }
    }

    if (showSettings) SettingsDialog { showSettings = false }
}

@Composable
fun ToolButton(emoji: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF2E3B55) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 22.sp)
        Text(label, fontSize = 11.sp, color = Color.LightGray)
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ExpandPanel(modifier: Modifier, imgW: Int, imgH: Int, vm: EditorViewModel) {
    val ctx = LocalContext.current
    var left by remember { mutableStateOf(true) }
    var right by remember { mutableStateOf(true) }
    var up by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    var pct by remember { mutableFloatStateOf(30f) }
    var prompt by remember { mutableStateOf("") }
    val hasKey = Prefs.apiKey(ctx).isNotBlank()

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = left, onClick = { left = !left }, label = { Text("Izquierda") })
            FilterChip(selected = right, onClick = { right = !right }, label = { Text("Derecha") })
            FilterChip(selected = up, onClick = { up = !up }, label = { Text("Arriba") })
            FilterChip(selected = down, onClick = { down = !down }, label = { Text("Abajo") })
        }
        LabeledSlider("Cantidad ${pct.roundToInt()}%", pct, 10f..100f) { pct = it }
        if (hasKey) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("¿Qué debería aparecer? (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                "Sin clave de Stability AI: se rellena con fondo difuminado. Agrega tu clave en ⚙ Ajustes para expandir con IA.",
                color = Color(0xFFFFCC80), fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val dx = (imgW * pct / 100f).roundToInt()
            val dy = (imgH * pct / 100f).roundToInt()
            vm.expand(
                if (left) dx else 0,
                if (right) dx else 0,
                if (up) dy else 0,
                if (down) dy else 0,
                prompt
            )
        }) { Text("↔️ Expandir") }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    var url by remember { mutableStateOf(Prefs.modelUrl(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes") },
        text = {
            Column {
                Text("Clave de Stability AI (para expandir imágenes con IA)", fontSize = 13.sp)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(12.dp))
                Text("URL del modelo de borrado (LaMa .onnx)", fontSize = 13.sp)
                OutlinedTextField(value = url, onValueChange = { url = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.setApiKey(ctx, key)
                Prefs.setModelUrl(ctx, url)
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun ImageCanvas(vm: EditorViewModel, paintMode: Boolean, brushPx: Float, adjust: FloatArray?) {
    val bmp = vm.image ?: return
    var box by remember { mutableStateOf(IntSize.Zero) }

    val iw = bmp.width.toFloat()
    val ih = bmp.height.toFloat()
    val scale = if (box.width > 0 && box.height > 0) min(box.width / iw, box.height / ih) else 1f
    val ox = (box.width - iw * scale) / 2f
    val oy = (box.height - ih * scale) / 2f

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(bmp, paintMode, scale, ox, oy, brushPx) {
                if (!paintMode) return@pointerInput
                var last: Offset? = null
                detectDragGestures(
                    onDragStart = { p ->
                        last = p
                        val ix = (p.x - ox) / scale
                        val iy = (p.y - oy) / scale
                        vm.strokeMask(ix, iy, ix, iy, brushPx / scale)
                    },
                    onDragEnd = { last = null },
                    onDragCancel = { last = null },
                    onDrag = { change, _ ->
                        val p = change.position
                        val l = last ?: p
                        vm.strokeMask((l.x - ox) / scale, (l.y - oy) / scale, (p.x - ox) / scale, (p.y - oy) / scale, brushPx / scale)
                        last = p
                        change.consume()
                    }
                )
            }
    ) {
        @Suppress("UNUSED_VARIABLE")
        val tick = vm.maskVersion // fuerza el redibujado al pintar
        val off = IntOffset(ox.roundToInt(), oy.roundToInt())
        val size = IntSize((iw * scale).roundToInt(), (ih * scale).roundToInt())
        drawImage(
            image = bmp.asImageBitmap(),
            dstOffset = off,
            dstSize = size,
            colorFilter = adjust?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
        )
        if (paintMode) {
            vm.mask?.let { drawImage(image = it.asImageBitmap(), dstOffset = off, dstSize = size, alpha = 0.55f) }
        }
    }
}
