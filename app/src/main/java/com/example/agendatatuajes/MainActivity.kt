package com.example.agendatatuajes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val GothicDarkColors = darkColorScheme(
    primary = Color(0xFFB71C1C),
    secondary = Color(0xFFE53935),
    background = Color(0xFF121212),
    surface = Color(0xFF1A1A1A),
    onPrimary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Moneda.fijar(getString(R.string.agenda_pais)) // € en España, $ (COP) en Colombia
        crearCanalNotificacion(this)
        setContent {
            MaterialTheme(colorScheme = GothicDarkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppAgendaPermanente()
                }
            }
        }
    }
}

private fun crearCanalNotificacion(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channelId = "citas_tatuajes_channel"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canal = NotificationChannel(channelId, "Avisos de citas", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(canal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAgendaPermanente() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val citaDao = database.citaDao()
    val gastoDao = database.gastoDao()
    val coroutineScope = rememberCoroutineScope()

    var modoApp by remember { mutableIntStateOf(0) } // 0:Pendientes, 1:Form, 2:Terminadas, 3:Cobros, 4:Galeria, 5:Camara, 6:Ganancias
    var citaAEditar by remember { mutableStateOf<Cita?>(null) }
    var citaParaAccion by remember { mutableStateOf<Cita?>(null) }
    var mostrarDialogoPago by remember { mutableStateOf(false) }
    var mostrarAjustes by remember { mutableStateOf(false) }
    var citaACobrar by remember { mutableStateOf<Cita?>(null) }

    val listaPendientes by citaDao.obtenerCitasPendientes().collectAsState(initial = emptyList())
    val listaTerminadas by citaDao.obtenerCitasTerminadas().collectAsState(initial = emptyList())
    val listaCobrosPendientes by citaDao.obtenerCobrosPendientes().collectAsState(initial = emptyList())
    val listaGaleria by citaDao.obtenerGaleria().collectAsState(initial = emptyList())
    val listaGanancias by citaDao.obtenerGanancias().collectAsState(initial = emptyList())
    val listaGastos by gastoDao.obtenerTodos().collectAsState(initial = emptyList())

    // 🔄 Conexión con n8n: guardar y borrar pasan SIEMPRE por CitaRepo (revisión + cola en una transacción).
    // El envío lo hace SyncN8n en segundo plano y no se pierde aunque no haya internet o n8n esté caído.
    fun borrarYSincronizar(cita: Cita) {
        coroutineScope.launch { CitaRepo.borrar(context, cita) }
    }
    // Android 13 o más nuevo: sin este permiso los avisos de las citas no se ven (antes nunca se pedía)
    val launcherPermisoNotificaciones = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (!concedido) Toast.makeText(context, "Sin permiso de notificaciones no podré avisarte de tus citas", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcherPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        reprogramarTodas(context) // por si el móvil se reinició o el sistema borró las alarmas
    }

    // Mientras la app está abierta: envía lo pendiente y mira qué han respondido los clientes (cada 15 min)
    LaunchedEffect(Unit) {
        SyncN8n.reintentarBloqueadas(context) // las rechazadas se reintentan al abrir la app (máx. 5 intentos)
        SyncN8n.solicitar(context) // al abrir la app, por si quedó algo de la última vez
        while (true) {
            SyncN8n.procesar(context)
            for (c in SyncN8n.consultarEstados(context, citaDao)) {
                cancelarAlarma(context, c) // el cliente ha cancelado: ya no hace falta avisar
                Toast.makeText(context, "❌ ${c.cliente} ha cancelado su cita del ${c.fecha} a las ${c.hora}", Toast.LENGTH_LONG).show()
            }
            kotlinx.coroutines.delay(15 * 60 * 1000L)
        }
    }

    // Lanzador para permisos de cámara
    val launcherPermisoCamara = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            modoApp = 5
        } else {
            Toast.makeText(context, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
        }
    }

    fun intentarAbrirCamara(cita: Cita) {
        citaParaAccion = cita
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            modoApp = 5
        } else {
            launcherPermisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    BackHandler {
        if (modoApp != 0) {
            modoApp = 0
            citaAEditar = null
            citaParaAccion = null
        } else {
            (context as? ComponentActivity)?.moveTaskToBack(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(when(modoApp) { 0->"PENDIENTES"; 1->if(citaAEditar==null)"NUEVA" else "EDITAR"; 2->"FINALIZADAS"; 3->"COBROS"; 4->"GALERÍA"; 6->"FINANZAS"; else->"CÁMARA" }, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A)),
                actions = {
                    if (modoApp != 0) IconButton(onClick = { modoApp = 0; citaAEditar = null; citaParaAccion = null }) { Icon(Icons.AutoMirrored.Filled.List, "Pendientes", tint = Color.White) }
                    if (modoApp != 6) IconButton(onClick = { modoApp = 6; citaParaAccion = null }) { Icon(Icons.Default.AttachMoney, "Ganancias", tint = Color.Green) }
                    if (modoApp != 3) IconButton(onClick = { modoApp = 3; citaParaAccion = null }) { Icon(Icons.Default.Payments, "Cobros", tint = Color.Yellow) }
                    if (modoApp != 2) IconButton(onClick = { modoApp = 2; citaParaAccion = null }) { Icon(Icons.Default.History, "Historial", tint = Color.White) }
                    if (modoApp != 4) IconButton(onClick = { modoApp = 4; citaParaAccion = null }) { Icon(Icons.Default.Collections, "Galería", tint = Color.White) }
                    if (modoApp != 1) IconButton(onClick = { modoApp = 1; citaAEditar = null; citaParaAccion = null }) { Icon(Icons.Default.Add, "Añadir", tint = Color.Red) }
                    IconButton(onClick = { mostrarAjustes = true }) { Icon(Icons.Default.Settings, "Ajustes", tint = Color.Gray) }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            Image(painter = painterResource(id = R.drawable.fondo), contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.2f), contentScale = ContentScale.Crop)

            when (modoApp) {
                0 -> PantallaListaCitas(listaPendientes, { modoApp = 1 }, { borrarYSincronizar(it) }, { citaAEditar = it; modoApp = 1 }, { citaParaAccion = it; mostrarDialogoPago = true }, { intentarAbrirCamara(it) }, false)
                1 -> FormularioCita(citaAEditar) { coroutineScope.launch { val c = CitaRepo.guardar(context, it.conFechaCobro()); if(!c.terminado) programarAlarmaLocal(context, c); modoApp = 0; citaAEditar = null } }
                2 -> PantallaListaCitas(listaTerminadas, { modoApp = 1 }, { borrarYSincronizar(it) }, { citaAEditar = it; modoApp = 1 }, {}, { intentarAbrirCamara(it) }, true)
                3 -> PantallaListaCitas(listaCobrosPendientes, { modoApp = 1 }, { borrarYSincronizar(it) }, { citaAEditar = it; modoApp = 1 }, {}, { intentarAbrirCamara(it) }, true, onCobrar = { citaACobrar = it })
                4 -> PantallaGaleria(listaGaleria) { cita ->
                    coroutineScope.launch {
                        // Solo se quita la foto: la cita y sus cobros se quedan (antes se borraba la cita entera)
                        val actual = citaDao.obtenerPorId(cita.id) ?: cita
                        borrarArchivoFoto(context, actual.fotoPath)
                        CitaRepo.guardar(context, actual.copy(fotoPath = null))
                        Toast.makeText(context, "Foto quitada", Toast.LENGTH_SHORT).show()
                    }
                }
                6 -> PantallaGanancias(
                    citas = listaGanancias,
                    gastos = listaGastos,
                    onGuardarGasto = { g -> coroutineScope.launch { gastoDao.guardar(g) } },
                    onBorrarGasto = { g -> coroutineScope.launch { gastoDao.borrar(g) } }
                )
                5 -> CameraWithFrameScreen(onImageCaptured = { bitmap ->
                    citaParaAccion?.let { cita ->
                        coroutineScope.launch {
                            try {
                                val file = File(context.getExternalFilesDir("Pictures"), "limpieza_${cita.id}.jpg")
                                val out = FileOutputStream(file)
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                out.flush()
                                out.close()
                                // Se parte de la cita guardada AHORA (no de una copia antigua) para no deshacer otros cambios
                                val actual = citaDao.obtenerPorId(cita.id) ?: cita
                                CitaRepo.guardar(context, actual.copy(fotoPath = Uri.fromFile(file).toString()))
                                modoApp = 0
                                citaParaAccion = null
                                Toast.makeText(context, "¡Foto guardada!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al guardar foto", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, onClose = { modoApp = 0; citaParaAccion = null })
            }

            if (mostrarAjustes) DialogoAjustes { mostrarAjustes = false }

            citaACobrar?.let { c ->
                DialogoRegistrarPago(c, { citaACobrar = null }) { actualizada ->
                    coroutineScope.launch {
                        CitaRepo.guardar(context, actualizada.conFechaCobro())
                        citaACobrar = null
                        Toast.makeText(context, "Pago apuntado", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (mostrarDialogoPago && citaParaAccion != null) {
                DialogoFinalizarCita(citaParaAccion!!, { mostrarDialogoPago = false }) { citaActualizada ->
                    coroutineScope.launch {
                        try {
                            val c = CitaRepo.guardar(context, citaActualizada.conFechaCobro()) // n8n la marca como terminada
                            cancelarAlarma(context, c)
                            mostrarDialogoPago = false
                            intentarAbrirCamara(c) // la foto se guarda sobre "c", así no se pierde la fecha de cobro
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al actualizar cita", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PantallaListaCitas(citas: List<Cita>, onAdd: () -> Unit, onDel: (Cita) -> Unit, onEdit: (Cita) -> Unit, onFinish: (Cita) -> Unit, onPhoto: (Cita) -> Unit, esHistorico: Boolean, onCobrar: ((Cita) -> Unit)? = null) {
    var citaABorrar by remember { mutableStateOf<Cita?>(null) }
    citaABorrar?.let { c ->
        DialogoConfirmar(
            titulo = "¿Borrar la cita de ${c.cliente}?",
            texto = "Se borra del todo (también sus cobros y su foto) y no se puede deshacer.",
            boton = "Borrar",
            onConfirmar = { citaABorrar = null; onDel(c) },
            onCancelar = { citaABorrar = null }
        )
    }
    if (citas.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No hay citas", color = Color.Gray)
                if (!esHistorico) Button(onClick = onAdd, Modifier.padding(top = 8.dp)) { Text("Añadir Cita") }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(citas) { cita ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(cita.cliente, style = MaterialTheme.typography.titleLarge, color = Color.Red)
                            Row {
                                if (!esHistorico) IconButton(onClick = { onFinish(cita) }) { Icon(Icons.Default.CheckCircle, null, tint = Color.Green) }
                                if (onCobrar != null) IconButton(onClick = { onCobrar(cita) }) { Icon(Icons.Default.Payments, "Registrar pago", tint = Color.Yellow) }
                                IconButton(onClick = { onEdit(cita) }) { Icon(Icons.Default.Edit, null, tint = Color.Gray) }
                                IconButton(onClick = { citaABorrar = cita }) { Icon(Icons.Default.Delete, "Borrar cita", tint = Color.DarkGray) }
                            }
                        }
                        Text("📅 ${cita.fecha} - ⏰ ${cita.hora}", color = Color.Gray)
                        if (!esHistorico) {
                            when (cita.estadoCliente) {
                                "confirmada" -> Text("✅ Confirmada por el cliente", color = Color(0xFF4CAF50))
                                "cancelada" -> Text("❌ Cancelada por el cliente", color = Color(0xFFFF5252))
                            }
                        }
                        if (cita.fotoPath != null) {
                            AsyncImage(model = cita.fotoPath, contentDescription = null, modifier = Modifier.fillMaxWidth().height(200.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📍 Dirección: ${cita.direccion}", color = Color.White)
                            if (cita.direccion.isNotEmpty()) {
                                val localContext = LocalContext.current
                                IconButton(onClick = {
                                    val uri = Uri.parse("geo:0,0?q=${Uri.encode(cita.direccion)}")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    intent.setPackage("com.google.android.apps.maps")
                                    try {
                                        localContext.startActivity(intent)
                                    } catch (e: Exception) {
                                        // Fallback
                                        localContext.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                }) {
                                    Icon(Icons.Default.Place, "Abrir en Maps", tint = Color.Red)
                                }
                            }
                        }
                        val ctxImporte = LocalContext.current
                        val totalCita = cita.total(ctxImporte)
                        val pendienteCita = calcularPendiente(totalCita, num(cita.abonado) ?: 0.0)
                        Text("💶 ${cita.detalleTotal(ctxImporte)}", color = Color.White)
                        Text("✅ Pagado: ${dinero(num(cita.abonado) ?: 0.0)}", color = Color.Green)
                        if (pendienteCita > 0) Text("⏳ PENDIENTE: ${dinero(pendienteCita)}", color = Color.Yellow)
                        Text("📞 ${cita.telefono}", color = Color.LightGray)
                        if (cita.notas.isNotEmpty()) Text("📝 ${cita.notas}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        val ctxCard = LocalContext.current
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            if (!esHistorico) {
                                // Recordatorio manual desde el WhatsApp de la profesional (gratis)
                                IconButton(onClick = { SyncN8n.abrirWhatsApp(ctxCard, cita.telefono, SyncN8n.textoRecordatorio(ctxCard, cita)) }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, "Recordar por WhatsApp", tint = Color(0xFF25D366))
                                }
                                // Enviar al cliente el enlace del bot de recordatorios de Telegram
                                IconButton(onClick = { SyncN8n.abrirWhatsApp(ctxCard, cita.telefono, SyncN8n.textoEnlaceTelegram(ctxCard, cita)) }) {
                                    Icon(Icons.Default.NotificationsActive, "Enviar enlace de recordatorios", tint = Color(0xFF29B6F6))
                                }
                            }
                            IconButton(onClick = { onPhoto(cita) }) { Icon(Icons.Default.PhotoCamera, null, tint = Color.Red) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormularioCita(cita: Cita?, onSave: (Cita) -> Unit) {
    val ctx = LocalContext.current
    // Las citas existentes se editan con su tipo de cobro; las nuevas, con el elegido en Ajustes
    val fijo = remember { cita?.esPrecioFijo ?: (modoCobro(ctx) == MODO_FIJO) }
    var tarifa by remember { mutableStateOf(cita?.let { mostrarNum(it.precioDeLaCita(ctx)) } ?: mostrarNum(precioHora(ctx))) }
    var totalFijo by remember { mutableStateOf(cita?.takeIf { it.esPrecioFijo }?.let { mostrarNum(it.total(ctx)) } ?: "") }
    var cliente by remember { mutableStateOf(cita?.cliente ?: "") }
    var tel by remember { mutableStateOf(cita?.telefono ?: "") }
    var email by remember { mutableStateOf(cita?.email ?: "") }
    // Solo dígitos (DDMMYYYY / HHMM): así, al editar sin tocar la fecha, no se rompe al guardar
    var fecha by remember { mutableStateOf(soloDigitos(cita?.fecha ?: "")) }
    var hora by remember { mutableStateOf(soloDigitos(cita?.hora ?: "")) }
    var direccion by remember { mutableStateOf(cita?.direccion ?: "") }
    var horas by remember { mutableStateOf(cita?.horas ?: "") }
    var abonado by remember { mutableStateOf(cita?.abonado ?: "") }
    val total = if (fijo) num(totalFijo) ?: 0.0 else (num(horas) ?: 0.0) * (num(tarifa) ?: 0.0)
    val pendiente = calcularPendiente(total, num(abonado) ?: 0.0)
    var notas by remember { mutableStateOf(cita?.notas ?: "") }
    
    var recordatorio by remember { mutableIntStateOf(cita?.recordatorio ?: 2) }
    var expandedRecordatorio by remember { mutableStateOf(false) }
    val opcionesRecordatorio = listOf(1, 2, 4, 6, 12, 24)

    val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Red)

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(cliente, { cliente = it }, label = { Text("Cliente") }, modifier = Modifier.fillMaxWidth(), colors = colors)
        OutlinedTextField(tel, { tel = it }, label = { Text("Teléfono") }, modifier = Modifier.fillMaxWidth(), colors = colors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Email del cliente (para recordatorios)") }, modifier = Modifier.fillMaxWidth(), colors = colors, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(fecha, { val d = soloDigitos(it); if(d.length<=8) fecha=d }, label = { Text("Fecha (DDMMYYYY)") }, modifier = Modifier.fillMaxWidth(), colors = colors, visualTransformation = DateVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(hora, { val d = soloDigitos(it); if(d.length<=4) hora=d }, label = { Text("Hora (HHMM)") }, modifier = Modifier.fillMaxWidth(), colors = colors, visualTransformation = TimeVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(direccion, { direccion = it }, label = { Text("Dirección") }, modifier = Modifier.fillMaxWidth(), colors = colors)
        if (!fijo) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(horas, { horas = it }, label = { Text("Horas") }, modifier = Modifier.weight(1f), colors = colors, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(tarifa, { tarifa = it }, label = { Text(conMoneda("Precio/hora")) }, modifier = Modifier.weight(1f), colors = colors, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fijo) OutlinedTextField(totalFijo, { totalFijo = it }, label = { Text(conMoneda("Precio total")) }, modifier = Modifier.weight(1f), colors = colors, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            else OutlinedTextField(mostrarNum(total), {}, label = { Text(conMoneda("Total")) }, modifier = Modifier.weight(1f), colors = colors, readOnly = true)
            OutlinedTextField(abonado, { abonado = it }, label = { Text(conMoneda("Abonado")) }, modifier = Modifier.weight(1f), colors = colors, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        OutlinedTextField(mostrarNum(pendiente), {}, label = { Text(conMoneda("Pendiente")) }, modifier = Modifier.fillMaxWidth(), colors = colors, readOnly = true)
        
        ExposedDropdownMenuBox(
            expanded = expandedRecordatorio,
            onExpandedChange = { expandedRecordatorio = !expandedRecordatorio },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = "$recordatorio horas antes",
                onValueChange = {},
                readOnly = true,
                label = { Text("Aviso Principal") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRecordatorio) },
                colors = colors,
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expandedRecordatorio,
                onDismissRequest = { expandedRecordatorio = false }
            ) {
                opcionesRecordatorio.forEach { opcion ->
                    DropdownMenuItem(
                        text = { Text("$opcion horas antes") },
                        onClick = {
                            recordatorio = opcion
                            expandedRecordatorio = false
                        }
                    )
                }
            }
        }
        
        OutlinedTextField(notas, { notas = it }, label = { Text("Notas") }, modifier = Modifier.fillMaxWidth(), colors = colors, minLines = 3)
        Button(onClick = { 
            val fechaHora = fechaHoraDeFormulario(fecha, hora)
            if (cliente.isNotEmpty() && fechaHora == null) {
                Toast.makeText(ctx, "Revisa la fecha (DDMMYYYY) y la hora (HHMM)", Toast.LENGTH_SHORT).show()
            }
            if(cliente.isNotEmpty() && fechaHora != null) {
                val (f, h) = fechaHora
                onSave(Cita(cita?.id ?: UUID.randomUUID().toString(), cliente, tel, f, h, direccion, horas.replace(',', '.'), notas, cita?.terminado?:false, guardarNum(num(abonado) ?: 0.0), guardarNum(pendiente), cita?.fotoPath, recordatorio, email = email, sincronizado = false, estadoCliente = if (cita != null && (f != cita.fecha || h != cita.hora)) "" else cita?.estadoCliente ?: "", /* nueva fecha: vuelve a estar pendiente de confirmar */ tarifaHora = if (fijo) "" else guardarNum(num(tarifa) ?: precioHora(ctx)), totalFijo = if (fijo) guardarNum(total) else "", fechaCobro = cita?.fechaCobro ?: ""))
            }
        }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("GUARDAR") }
    }
}

@Composable
fun DialogoFinalizarCita(cita: Cita, onDismiss: () -> Unit, onConfirm: (Cita) -> Unit) {
    val ctx = LocalContext.current
    val precioTotal = cita.total(ctx)
    val yaAbonado = num(cita.abonado) ?: 0.0
    var mostrarPartePago by remember { mutableStateOf(false) }
    var extraAmount by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("¿Cómo se ha abonado?", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cliente: ${cita.cliente}", color = Color.Gray)
                if (!cita.esPrecioFijo) Text("Horas: ${cita.horas} × ${dinero(cita.precioDeLaCita(ctx))}/h", color = Color.White)
                Text("Precio Total: ${dinero(precioTotal)}", color = Color.White)
                Text("Ya abonado: ${dinero(yaAbonado)}", color = Color.Green)
                Text("Pendiente: ${dinero(calcularPendiente(precioTotal, yaAbonado))}", color = Color.Yellow)
                
                if (mostrarPartePago) {
                    OutlinedTextField(
                        value = extraAmount,
                        onValueChange = { extraAmount = it },
                        label = { Text(conMoneda("Abonar ahora")) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!mostrarPartePago) {
                    Button(
                        onClick = {
                            onConfirm(cita.copy(terminado = true, abonado = guardarNum(max(precioTotal, yaAbonado)), pendiente = "0", tarifaHora = if (cita.esPrecioFijo) "" else guardarNum(cita.precioDeLaCita(ctx))))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                    ) { Text("Pagado y Foto", color = Color.Black) }
                    
                    Button(
                        onClick = { mostrarPartePago = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)
                    ) { Text("Parte de pago y Foto", color = Color.Black) }
                } else {
                    Button(
                        onClick = {
                            val extra = num(extraAmount) ?: 0.0
                            val nuevoAbonado = yaAbonado + extra
                            onConfirm(cita.copy(
                                terminado = true,
                                abonado = guardarNum(nuevoAbonado),
                                pendiente = guardarNum(calcularPendiente(precioTotal, nuevoAbonado)),
                                tarifaHora = if (cita.esPrecioFijo) "" else guardarNum(cita.precioDeLaCita(ctx))
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) { Text("Confirmar y Foto") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) }
        }
    )
}

@Composable
fun PantallaGaleria(citas: List<Cita>, onQuitarFoto: (Cita) -> Unit) {
    val context = LocalContext.current
    var fotoAQuitar by remember { mutableStateOf<Cita?>(null) }
    fotoAQuitar?.let { c ->
        DialogoConfirmar(
            titulo = "¿Quitar la foto de ${c.cliente}?",
            texto = "Solo se quita la foto. La cita y sus cobros no se tocan.",
            boton = "Quitar foto",
            onConfirmar = { fotoAQuitar = null; onQuitarFoto(c) },
            onCancelar = { fotoAQuitar = null }
        )
    }
    if (citas.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Galería vacía", color = Color.Gray) }
    else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(citas) { cita ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
                Column(Modifier.padding(8.dp)) {
                    AsyncImage(model = cita.fotoPath, contentDescription = null, modifier = Modifier.fillMaxWidth().height(350.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(cita.cliente, color = Color.White)
                        Row {
                            IconButton(onClick = { compartir(context, cita.fotoPath, "com.instagram.android") }) { Icon(Icons.Default.Share, null, tint = Color(0xFFE1306C)) }
                            IconButton(onClick = { compartir(context, cita.fotoPath, "com.whatsapp") }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color(0xFF25D366)) }
                            IconButton(onClick = { fotoAQuitar = cita }) { Icon(Icons.Default.Delete, "Quitar foto", tint = Color.Gray) }
                        }
                    }
                }
            }
        }
    }
}

fun compartir(context: Context, path: String?, pkg: String) {
    if (path == null) return
    
    try {
        val fileUri = Uri.parse(path)
        val file = File(fileUri.path ?: return)
        
        // Creamos el URI seguro con FileProvider
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Intentamos forzar la app, pero si falla lo capturamos
            if (pkg.isNotEmpty()) setPackage(pkg)
        }

        context.startActivity(Intent.createChooser(intent, "Compartir trabajo"))
        
    } catch (e: Exception) {
        // Si falla por el paquete (setPackage), intentamos compartir sin paquete
        try {
            val fileUri = Uri.parse(path)
            val file = File(fileUri.path ?: return)
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            
            val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(fallbackIntent, "La app específica no está, elige otra:"))
        } catch (e2: Exception) {
            Toast.makeText(context, "No se puede compartir la imagen", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun CameraWithFrameScreen(onImageCaptured: (Bitmap) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
            val previewView = PreviewView(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }, Modifier.fillMaxSize())

        // Marco Tattoo superpuesto
        Image(painter = painterResource(R.drawable.marco), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

        IconButton(onClick = onClose, Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.Default.Close, null, tint = Color.White) }

        Button(onClick = {
            val capture = imageCapture ?: return@Button
            val file = File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
            
            capture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val capturedBitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (capturedBitmap != null) {
                        val frameBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.marco)
                        val result = mergeBitmaps(capturedBitmap, frameBitmap)
                        onImageCaptured(result)
                        file.delete()
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(context, "Error al capturar: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
            Icon(Icons.Default.Camera, null)
            Spacer(Modifier.width(8.dp))
            Text("CAPTURAR")
        }
    }
}

fun mergeBitmaps(captured: Bitmap, frame: Bitmap): Bitmap {
    val size = 1080
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    
    // Dibujar la foto centrada y recortada a 1:1
    val side = Math.min(captured.width, captured.height)
    val srcRect = Rect((captured.width - side) / 2, (captured.height - side) / 2, (captured.width + side) / 2, (captured.height + side) / 2)
    canvas.drawBitmap(captured, srcRect, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
    
    // Dibujar el marco encima
    canvas.drawBitmap(Bitmap.createScaledBitmap(frame, size, size, true), 0f, 0f, null)
    
    return result
}

// programarAlarmaLocal() y cancelarAlarma() están en Alarmas.kt

/** Borra el archivo de una foto de la agenda (solo si está dentro de la carpeta de la app). */
fun borrarArchivoFoto(context: Context, ruta: String?) {
    try {
        val f = File(Uri.parse(ruta ?: return).path ?: return)
        val carpeta = context.getExternalFilesDir(null)?.canonicalPath ?: return
        if (f.canonicalPath.startsWith(carpeta)) f.delete()
    } catch (e: Exception) { /* si no se puede borrar el archivo, al menos la cita deja de apuntar a él */ }
}

@Composable
fun DialogoConfirmar(titulo: String, texto: String, boton: String, onConfirmar: () -> Unit, onCancelar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancelar,
        containerColor = Color(0xFF1A1A1A),
        title = { Text(titulo, color = Color.White) },
        text = { Text(texto, color = Color.Gray) },
        confirmButton = { Button(onClick = onConfirmar, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))) { Text(boton, color = Color.White) } },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar", color = Color.White) } }
    )
}

// La pestaña $ (PantallaGanancias) está en Finanzas.kt
