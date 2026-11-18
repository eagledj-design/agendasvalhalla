package com.example.agendatatuajes

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * Modo de cobro, precio por hora y cálculo de cobros.
 *
 * - Modo "horas": Total = horas × precio/hora (limpiadoras, cuidadoras, niñeras...).
 * - Modo "fijo":  Total = precio cerrado que pone en cada cita (tatuadoras, manicuristas...).
 *   El modo se elige en Ajustes; cada cita recuerda cómo se cobró aunque luego se cambie el modo.
 *
 * - Cada profesional pone SU precio por hora en Ajustes (rueda dentada). Se guarda en el móvil.
 *   Si nunca lo ha tocado, se usa el de config.xml (agenda_precio_hora).
 * - Cada cita guarda el precio por hora con el que se creó: si un día sube la tarifa,
 *   las citas antiguas NO cambian de importe. También se puede poner un precio especial en una cita.
 * - Total = horas × precio/hora · Pendiente = Total − Abonado (nunca negativo).
 */

private const val PREFS = "ajustes_agenda"
private const val KEY_PRECIO = "precio_hora"
private const val KEY_MODO = "modo_cobro"

const val MODO_HORAS = "horas"
const val MODO_FIJO = "fijo"

/** "horas" o "fijo": lo elegido en Ajustes o, si no, config.xml. */
fun modoCobro(context: Context): String {
    val guardado = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODO, null)
    val modo = (guardado ?: context.getString(R.string.agenda_modo_cobro)).trim().lowercase(Locale.ROOT)
    return if (modo == MODO_FIJO) MODO_FIJO else MODO_HORAS
}

fun guardarModoCobro(context: Context, modo: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODO, modo).apply()
}

/** Convierte "12,5" o "12.5" en 12.5 (null si no es un número). */
fun num(texto: String?): Double? = texto?.trim()?.replace(',', '.')?.toDoubleOrNull()

/** Número para guardar: "36", "36.5", "36.75" (punto decimal, máx. 2 decimales). */
fun guardarNum(valor: Double): String {
    val centimos = (valor * 100).roundToLong()
    return if (centimos % 100 == 0L) (centimos / 100).toString()
    else String.format(Locale.US, "%.2f", centimos / 100.0).trimEnd('0')
}

/** Número para mostrar: "36", "36,50". */
fun mostrarNum(valor: Double): String {
    val centimos = (valor * 100).roundToLong()
    return if (centimos % 100 == 0L) (centimos / 100).toString()
    else String.format(Locale("es", "ES"), "%.2f", centimos / 100.0)
}

/** Precio por hora general de la agenda (Ajustes o, si no hay, config.xml). */
fun precioHora(context: Context): Double {
    val guardado = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PRECIO, null)
    return num(guardado) ?: num(context.getString(R.string.agenda_precio_hora)) ?: 12.0
}

fun guardarPrecioHora(context: Context, precio: Double) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PRECIO, guardarNum(precio)).apply()
}

/** Precio por hora de ESTA cita (el suyo propio o, en citas antiguas, el general). */
fun Cita.precioDeLaCita(context: Context): Double = num(tarifaHora) ?: precioHora(context)

/** true si esta cita se cobra con precio cerrado. */
val Cita.esPrecioFijo: Boolean get() = totalFijo.isNotBlank()

fun Cita.total(context: Context): Double =
    if (esPrecioFijo) num(totalFijo) ?: 0.0
    else (num(horas) ?: 0.0) * precioDeLaCita(context)

/** "3 h × 15 €/h = 45 €" o "45 €" según el tipo de cobro de la cita. */
fun Cita.detalleTotal(context: Context): String =
    if (esPrecioFijo) "${mostrarNum(total(context))} €"
    else "${horas.ifBlank { "0" }} h × ${mostrarNum(precioDeLaCita(context))} €/h = ${mostrarNum(total(context))} €"

fun calcularPendiente(total: Double, abonado: Double): Double = max(0.0, total - abonado)

/** Hoy en formato "dd/MM/yyyy". */
fun hoy(): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())

/**
 * Pone la fecha de cobro cuando la cita queda terminada y sin nada pendiente
 * (respeta la que ya tuviera) y la quita si vuelve a deber algo.
 * Llamar SIEMPRE antes de guardar una cita.
 */
fun Cita.conFechaCobro(): Cita =
    if (terminado && (num(pendiente) ?: 0.0) <= 0.0) copy(fechaCobro = fechaCobro.ifBlank { hoy() })
    else copy(fechaCobro = "")

/** "11/2026" a partir de "dd/MM/yyyy". */
fun mesDe(fecha: String): String = if (fecha.length >= 7) fecha.takeLast(7) else ""

/** Ajustes: forma de cobrar y precio por hora. */
@Composable
fun DialogoAjustes(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var modo by remember { mutableStateOf(modoCobro(ctx)) }
    var precio by remember { mutableStateOf(mostrarNum(precioHora(ctx))) }
    val valido = modo == MODO_FIJO || (num(precio) ?: -1.0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("Ajustes", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("¿Cómo cobras?", color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { modo = MODO_HORAS }) {
                    RadioButton(selected = modo == MODO_HORAS, onClick = { modo = MODO_HORAS })
                    Text("Por horas (horas × precio/hora)", color = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { modo = MODO_FIJO }) {
                    RadioButton(selected = modo == MODO_FIJO, onClick = { modo = MODO_FIJO })
                    Text("Precio cerrado por trabajo", color = Color.White)
                }
                if (modo == MODO_HORAS) {
                    OutlinedTextField(
                        value = precio,
                        onValueChange = { precio = it },
                        label = { Text("Precio por hora (€)") },
                        singleLine = true,
                        isError = !valido,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("Se aplica a las citas nuevas. Las ya creadas mantienen su precio.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = valido, onClick = {
                guardarModoCobro(ctx, modo)
                if (modo == MODO_HORAS) guardarPrecioHora(ctx, num(precio)!!)
                onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )
}

/** Apuntar un pago en una cita ya terminada que tiene algo pendiente (pantalla Cobros). */
@Composable
fun DialogoRegistrarPago(cita: Cita, onDismiss: () -> Unit, onConfirm: (Cita) -> Unit) {
    val ctx = LocalContext.current
    val total = cita.total(ctx)
    val abonado = num(cita.abonado) ?: 0.0
    val pendiente = calcularPendiente(total, abonado)
    var importe by remember { mutableStateOf("") }

    fun aplicar(pago: Double) {
        val nuevoAbonado = abonado + pago
        onConfirm(cita.copy(abonado = guardarNum(nuevoAbonado), pendiente = guardarNum(calcularPendiente(total, nuevoAbonado)), sincronizado = false))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("Registrar pago", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cliente: ${cita.cliente}", color = Color.Gray)
                Text("Total: ${cita.detalleTotal(ctx)}", color = Color.White)
                Text("Ya abonado: ${mostrarNum(abonado)} €", color = Color.Green)
                Text("Pendiente: ${mostrarNum(pendiente)} €", color = Color.Yellow)
                OutlinedTextField(
                    value = importe, onValueChange = { importe = it },
                    label = { Text("Abona ahora (€)") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { aplicar(pendiente) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Green)) { Text("Lo ha pagado todo", color = Color.Black) }
                Button(enabled = (num(importe) ?: 0.0) > 0, onClick = { aplicar(num(importe)!!) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)) { Text("Apuntar este pago", color = Color.Black) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )
}
