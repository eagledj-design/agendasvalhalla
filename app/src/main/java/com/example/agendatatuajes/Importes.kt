package com.example.agendatatuajes

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Importes y moneda (Kotlin puro, sin Android: se prueba con tests normales).
 *
 * La moneda NO se saca del GPS ni del idioma del móvil: sale de config.xml (agenda_pais), que es
 * el país del NEGOCIO. Así una clienta colombiana de viaje en España sigue viendo pesos, y nunca
 * hace falta pedir permiso de ubicación.
 *   ES → "36,50 €"      (2 decimales, coma decimal)
 *   CO → "$150.000"     (sin decimales, punto de miles)
 * Los importes se GUARDAN siempre igual en los dos países ("150000", "36.5"): solo cambia cómo se ven.
 */
object Moneda {
    @Volatile
    var pais: String = "ES"
        private set

    /** Se llama al abrir la app con el valor de agenda_pais. Cualquier cosa que no sea CO cuenta como ES. */
    fun fijar(codigo: String?) {
        pais = if (codigo?.trim()?.uppercase(Locale.ROOT) == "CO") "CO" else "ES"
    }

    val esPesos: Boolean get() = pais == "CO"
    val simbolo: String get() = if (esPesos) "$" else "€"
}

private val MILES_PUNTO = Regex("^-?\\d{1,3}(\\.\\d{3})+$")
private val MILES_COMA = Regex("^-?\\d{1,3}(,\\d{3})+$")

/**
 * Convierte lo que escribe la profesional en un número (null si no lo es).
 * "12,5" y "12.5" → 12.5 · "150.000" y "1.500.000" → miles · "1.500,50" → 1500.5 · "$ 150.000" → 150000.
 * Los importes guardados ("36.5", "150000") nunca tienen 3 decimales, así que no se confunden con miles.
 */
fun num(texto: String?): Double? {
    var t = texto?.trim()?.replace(" ", "")?.replace(" ", "")?.removePrefix("$")?.removeSuffix("€") ?: return null
    if (t.isEmpty()) return null
    t = when {
        t.contains('.') && t.contains(',') ->
            if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        MILES_PUNTO.matches(t) -> t.replace(".", "")
        MILES_COMA.matches(t) && Moneda.esPesos -> t.replace(",", "")   // en pesos no hay céntimos
        else -> t.replace(',', '.')
    }
    return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}

/** Número para guardar: "36", "36.5", "36.75" (punto decimal, máx. 2 decimales). Igual en los dos países. */
fun guardarNum(valor: Double): String {
    val centimos = (valor * 100).roundToLong()
    return if (centimos % 100 == 0L) (centimos / 100).toString()
    else String.format(Locale.US, "%.2f", centimos / 100.0).trimEnd('0')
}

/** Número para mostrar, sin símbolo: ES "36" / "36,50" · CO "150.000". */
fun mostrarNum(valor: Double): String {
    if (Moneda.esPesos) {
        val pesos = valor.roundToLong()
        val miles = abs(pesos).toString().reversed().chunked(3).joinToString(".").reversed()
        return if (pesos < 0) "-$miles" else miles
    }
    val centimos = (valor * 100).roundToLong()
    return if (centimos % 100 == 0L) (centimos / 100).toString()
    else String.format(Locale("es", "ES"), "%.2f", centimos / 100.0)
}

/** Importe con su moneda: ES "36,50 €" · CO "$150.000". Los negativos llevan "−" delante. */
fun dinero(valor: Double): String {
    val signo = if (valor < 0 && mostrarNum(abs(valor)) != "0") "− " else ""
    val n = mostrarNum(abs(valor))
    return if (Moneda.esPesos) "$signo$$n" else "$signo$n €"
}

/** Etiqueta de un campo de importe: "Abonado (€)" · "Abonado ($)". */
fun conMoneda(etiqueta: String): String = "$etiqueta (${Moneda.simbolo})"
