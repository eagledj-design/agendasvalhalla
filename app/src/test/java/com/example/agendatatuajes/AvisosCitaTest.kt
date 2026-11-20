package com.example.agendatatuajes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Cuándo suenan los avisos: nunca en el pasado (al reiniciar el móvil no deben saltar todos de golpe). */
class AvisosCitaTest {
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")
    private fun ms(s: String) = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { timeZone = madrid }.parse(s)!!.time

    @Test fun citaFutura_dosAvisos() {
        val a = avisosDeCita("c1", "25/11/2026", "18:00", 2, ms("20/11/2026 10:00"), madrid)
        assertEquals(listOf(ms("25/11/2026 16:00"), ms("25/11/2026 17:30")), a.map { it.enMillis })
        assertEquals(listOf("2 horas", "30 minutos"), a.map { it.antelacion })
        assertEquals(codigosAviso("c1"), a.map { it.codigo })
    }

    @Test fun avisoPrincipalYaPasado_soloQuedaElDeMediaHora() {
        val a = avisosDeCita("c1", "20/11/2026", "18:00", 24, ms("20/11/2026 10:00"), madrid)
        assertEquals(listOf("30 minutos"), a.map { it.antelacion })
    }

    @Test fun citaPasada_ningunAviso() {
        assertTrue(avisosDeCita("c1", "19/11/2026", "18:00", 2, ms("20/11/2026 10:00"), madrid).isEmpty())
    }

    @Test fun fechaRota_ningunAvisoYNoRevienta() {
        assertTrue(avisosDeCita("c1", "31/02/2026", "18:00", 2, 0L, madrid).isEmpty())
        assertTrue(avisosDeCita("c1", "", "", 2, 0L, madrid).isEmpty())
        assertTrue(avisosDeCita("c1", "22//1/1/2026", "18::00", 2, 0L, madrid).isEmpty())
    }

    @Test fun unaHora_enSingular() {
        assertEquals("1 hora", avisosDeCita("c1", "25/11/2026", "18:00", 1, 0L, madrid)[0].antelacion)
    }

    @Test fun codigosDistintosPorCitaYEstables() {
        assertEquals(codigosAviso("abc"), codigosAviso("abc"))
        assertEquals(2, codigosAviso("abc").toSet().size)
    }
}
