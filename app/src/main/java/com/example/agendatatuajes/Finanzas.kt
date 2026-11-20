package com.example.agendatatuajes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Pestaña $ (Finanzas): Ingresos (citas pagadas del todo, por mes de cobro) y Gastos del mes.
 * Beneficio = Cobrado − Gastos. Todo se calcula al vuelo: no hay contadores que reiniciar.
 */

private val VERDE = Color(0xFF4CAF50)
private val ROJO = Color(0xFFFF5252)
private val FONDO_TARJETA = Color(0xFF1A1A1A)

private fun claveOrden(mes: String): String = // "11/2026" → "202611"
    if (mes.length == 7) mes.substring(3) + mes.substring(0, 2) else "000000"

private fun fechaValida(ddmmyyyy: String): Boolean = try {
    SimpleDateFormat("ddMMyyyy", Locale.US).apply { isLenient = false }.parse(ddmmyyyy) != null
} catch (e: Exception) { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaGanancias(
    citas: List<Cita>,
    gastos: List<Gasto>,
    onGuardarGasto: (Gasto) -> Unit,
    onBorrarGasto: (Gasto) -> Unit
) {
    val context = LocalContext.current
    val mesActual = SimpleDateFormat("MM/yyyy", Locale.US).format(Date())
    var mesSeleccionado by remember { mutableStateOf(mesActual) }
    var pestana by remember { mutableIntStateOf(0) } // 0 Ingresos · 1 Gastos
    var expanded by remember { mutableStateOf(false) }
    var gastoEditando by remember { mutableStateOf<Gasto?>(null) }
    var mostrarNuevoGasto by remember { mutableStateOf(false) }
    var gastoABorrar by remember { mutableStateOf<Gasto?>(null) }

    val mesesDisponibles = (citas.map { mesDe(it.fechaCobro) } + gastos.map { mesDe(it.fecha) } + mesActual)
        .filter { it.length == 7 }.distinct().sortedByDescending { claveOrden(it) }

    val ingresosMes = citas.filter { mesDe(it.fechaCobro) == mesSeleccionado }
    val gastosMes = gastos.filter { mesDe(it.fecha) == mesSeleccionado }
    val cobrado = ingresosMes.sumOf { num(it.abonado) ?: 0.0 }
    val gastado = gastosMes.sumOf { num(it.importe) ?: 0.0 }
    val beneficio = cobrado - gastado

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // ── Selector de mes ──
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = "Mes: $mesSeleccionado", onValueChange = {}, readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = VERDE),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(FONDO_TARJETA)) {
                    mesesDisponibles.forEach { mes ->
                        DropdownMenuItem(text = { Text(mes, color = Color.White) }, onClick = { mesSeleccionado = mes; expanded = false })
                    }
                }
            }

            // ── Resumen del mes ──
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = FONDO_TARJETA)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilaResumen("💰 Cobrado", dinero(cobrado), Color.White)
                    FilaResumen("🧾 Gastos", "− ${dinero(gastado)}", ROJO)
                    HorizontalDivider(color = Color.DarkGray)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("BENEFICIO", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                        Text(
                            dinero(beneficio),
                            color = if (beneficio < 0) ROJO else VERDE,
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── Ingresos | Gastos ──
            TabRow(selectedTabIndex = pestana, containerColor = Color.Transparent, contentColor = Color.White) {
                Tab(selected = pestana == 0, onClick = { pestana = 0 }, text = { Text("Ingresos (${ingresosMes.size})") })
                Tab(selected = pestana == 1, onClick = { pestana = 1 }, text = { Text("Gastos (${gastosMes.size})") })
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (pestana == 0) {
                    if (ingresosMes.isEmpty()) {
                        Text("No hay trabajos cobrados en $mesSeleccionado", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 150.dp)) {
                            items(ingresosMes) { cita ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FONDO_TARJETA)) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(cita.cliente, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                            Text("Cobrada el ${cita.fechaCobro}" + if (cita.fechaCobro != cita.fecha) " · cita del ${cita.fecha}" else "",
                                                color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text("+ ${dinero(num(cita.abonado) ?: 0.0)}", color = VERDE, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (gastosMes.isEmpty()) {
                        Text("No hay gastos en $mesSeleccionado.\nPulsa ➕ para apuntar uno.", color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 150.dp)) {
                            items(gastosMes, key = { it.id }) { gasto ->
                                Card(Modifier.fillMaxWidth().clickable { gastoEditando = gasto }, colors = CardDefaults.cardColors(containerColor = FONDO_TARJETA)) {
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(gasto.concepto, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                            Text("${gasto.fecha} · ${gasto.categoria}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text("− ${dinero(num(gasto.importe) ?: 0.0)}", color = ROJO, style = MaterialTheme.typography.titleMedium)
                                        IconButton(onClick = { gastoABorrar = gasto }) { Icon(Icons.Default.Delete, "Borrar gasto", tint = Color.DarkGray) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Aviso legal ──
            Text(
                "⚠️ Control orientativo de ingresos y gastos. No es contabilidad oficial: no calcula IVA ni impuestos. Consulta con tu gestor.",
                color = ROJO, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }

        // ── Botones flotantes ──
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
            if (pestana == 1) {
                FloatingActionButton(onClick = { mostrarNuevoGasto = true }, containerColor = ROJO, contentColor = Color.White) {
                    Icon(Icons.Default.Add, "Añadir gasto")
                }
            }
            FloatingActionButton(
                onClick = {
                    val texto = buildString {
                        appendLine("Resumen del mes $mesSeleccionado")
                        appendLine()
                        appendLine("Cobrado: ${dinero(cobrado)}")
                        appendLine("Gastos: ${dinero(gastado)}")
                        appendLine("Beneficio: ${dinero(beneficio)}")
                        appendLine()
                        appendLine("INGRESOS")
                        if (ingresosMes.isEmpty()) appendLine("- (ninguno)")
                        ingresosMes.forEach { appendLine("- ${it.fechaCobro}: ${it.cliente} → ${dinero(num(it.abonado) ?: 0.0)}") }
                        appendLine()
                        appendLine("GASTOS")
                        if (gastosMes.isEmpty()) appendLine("- (ninguno)")
                        gastosMes.forEach { appendLine("- ${it.fecha}: ${it.concepto} (${it.categoria}) → ${dinero(num(it.importe) ?: 0.0)}") }
                        appendLine()
                        append("Control orientativo, no es contabilidad oficial (no incluye IVA ni impuestos).")
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Resumen ingresos y gastos - $mesSeleccionado")
                        putExtra(Intent.EXTRA_TEXT, texto)
                    }
                    context.startActivity(Intent.createChooser(intent, "Enviar resumen mensual por..."))
                },
                containerColor = VERDE, contentColor = Color.Black
            ) { Icon(Icons.Default.Email, contentDescription = "Enviar resumen por correo") }
        }
    }

    // ── Diálogos ──
    if (mostrarNuevoGasto || gastoEditando != null) {
        DialogoGasto(
            gasto = gastoEditando,
            onDismiss = { mostrarNuevoGasto = false; gastoEditando = null },
            onGuardar = { onGuardarGasto(it); mostrarNuevoGasto = false; gastoEditando = null; mesSeleccionado = mesDe(it.fecha) }
        )
    }
    gastoABorrar?.let { g ->
        AlertDialog(
            onDismissRequest = { gastoABorrar = null },
            containerColor = FONDO_TARJETA,
            title = { Text("¿Borrar gasto?", color = Color.White) },
            text = { Text("${g.concepto} · ${dinero(num(g.importe) ?: 0.0)}", color = Color.Gray) },
            confirmButton = { Button(onClick = { onBorrarGasto(g); gastoABorrar = null }, colors = ButtonDefaults.buttonColors(containerColor = ROJO)) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { gastoABorrar = null }) { Text("Cancelar", color = Color.White) } }
        )
    }
}

@Composable
private fun FilaResumen(etiqueta: String, valor: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(etiqueta, color = Color.Gray)
        Text(valor, color = color, style = MaterialTheme.typography.titleMedium)
    }
}

/** Nuevo gasto o edición de uno existente. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogoGasto(gasto: Gasto?, onDismiss: () -> Unit, onGuardar: (Gasto) -> Unit) {
    var concepto by remember { mutableStateOf(gasto?.concepto ?: "") }
    var importe by remember { mutableStateOf(gasto?.let { mostrarNum(num(it.importe) ?: 0.0) } ?: "") }
    var fecha by remember { mutableStateOf((gasto?.fecha ?: hoy()).replace("/", "")) }
    var categoria by remember { mutableStateOf(gasto?.categoria ?: CATEGORIAS_GASTO.first()) }
    var expanded by remember { mutableStateOf(false) }
    val colores = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = ROJO)

    val importeOk = (num(importe) ?: 0.0) > 0
    val fechaOk = fecha.length == 8 && fechaValida(fecha)
    val valido = concepto.isNotBlank() && importeOk && fechaOk

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FONDO_TARJETA,
        title = { Text(if (gasto == null) "Nuevo gasto" else "Editar gasto", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(concepto, { concepto = it }, label = { Text("Concepto (tintas, esmaltes...)") }, singleLine = true, colors = colores, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(importe, { importe = it }, label = { Text(conMoneda("Importe")) }, singleLine = true, isError = importe.isNotEmpty() && !importeOk,
                    colors = colores, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fecha, { if (it.length <= 8) fecha = it }, label = { Text("Fecha (DDMMYYYY)") }, singleLine = true, isError = fecha.length == 8 && !fechaOk,
                    colors = colores, visualTransformation = DateVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(categoria, {}, readOnly = true, label = { Text("Categoría") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = colores, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        CATEGORIAS_GASTO.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { categoria = c; expanded = false }) }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = valido, onClick = {
                val f = "${fecha.substring(0, 2)}/${fecha.substring(2, 4)}/${fecha.substring(4)}"
                onGuardar(Gasto(id = gasto?.id ?: java.util.UUID.randomUUID().toString(), concepto = concepto.trim(),
                    importe = guardarNum(num(importe)!!), fecha = f, categoria = categoria))
            }, colors = ButtonDefaults.buttonColors(containerColor = ROJO)) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )
}
