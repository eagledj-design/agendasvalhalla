package com.example.agendatatuajes

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Cuándo suenan los avisos de una cita (Kotlin puro, se prueba sin Android).
 * Cada cita tiene dos avisos: el principal (las horas que elige la profesional) y otro fijo 30 min antes.
 * Los que ya han pasado NO se programan: si no, al reiniciar el móvil saltarían todos de golpe.
 */
data class Aviso(val codigo: Int, val enMillis: Long, val antelacion: String)

/** Códigos de las dos alarmas de una cita (los mismos para programar y para cancelar). */
fun codigosAviso(idCita: String): List<Int> = listOf(idCita.hashCode(), idCita.hashCode() + 100000)

fun avisosDeCita(
    idCita: String, fecha: String, hora: String, horasAntes: Int, ahora: Long,
    zona: TimeZone = TimeZone.getDefault(),
): List<Aviso> {
    val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { isLenient = false; timeZone = zona }
    val cita = try { formato.parse("$fecha $hora")?.time } catch (e: Exception) { null } ?: return emptyList()
    val (principal, mediaHora) = codigosAviso(idCita)
    val horas = horasAntes.coerceIn(1, 72)
    return listOf(
        Aviso(principal, cita - horas * 3_600_000L, "$horas ${if (horas == 1) "hora" else "horas"}"),
        Aviso(mediaHora, cita - 30 * 60_000L, "30 minutos"),
    ).filter { it.enMillis > ahora }
}
