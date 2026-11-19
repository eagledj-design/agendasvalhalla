package com.example.agendatatuajes

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Fecha y hora del formulario de citas. Kotlin puro (se prueba en la JVM).
 *
 * Arregla un fallo antiguo: al EDITAR una cita, el formulario cargaba la fecha guardada con barras
 * ("22/11/2026") y, si no se tocaba ese campo, al guardar la troceaba como si fueran 8 dígitos y quedaba
 * "22//1/1/2026". Con la hora pasaba lo mismo ("18::00"). Esa fecha rota rompía las alarmas y n8n
 * rechazaba la cita por no tener fecha_hora.
 */

/** Solo los dígitos: "22/11/2026" → "22112026", "18:30" → "1830". */
fun soloDigitos(texto: String): String = texto.filter { it.isDigit() }

/**
 * Convierte lo que hay en el formulario (con o sin separadores) en ("dd/MM/yyyy", "HH:mm").
 * Devuelve null si la fecha u hora no existen (32/13/2026, 25:61...).
 */
fun fechaHoraDeFormulario(fecha: String, hora: String): Pair<String, String>? {
    val f = soloDigitos(fecha)
    val h = soloDigitos(hora)
    if (f.length != 8 || h.length != 4) return null
    val fechaTexto = "${f.substring(0, 2)}/${f.substring(2, 4)}/${f.substring(4)}"
    val horaTexto = "${h.substring(0, 2)}:${h.substring(2)}"
    return try {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { isLenient = false }.parse("$fechaTexto $horaTexto")
        fechaTexto to horaTexto
    } catch (e: ParseException) {
        null
    }
}
