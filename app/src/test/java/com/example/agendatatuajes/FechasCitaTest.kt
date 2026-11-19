package com.example.agendatatuajes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El formulario de citas ya no rompe la fecha ni la hora al editar. */
class FechasCitaTest {
    @Test fun nuevaCita_digitos() {
        assertEquals("22/11/2026" to "18:30", fechaHoraDeFormulario("22112026", "1830"))
    }

    @Test fun editarSinTocarLaFecha_noLaRompe() {
        // Antes: "22/11/2026" se troceaba como 8 dígitos → "22//1/1/2026"
        assertEquals("22/11/2026" to "19:00", fechaHoraDeFormulario("22/11/2026", "1900"))
    }

    @Test fun editarSinTocarLaHora_noLaRompe() {
        // Antes: "18:00" → "18::00"
        assertEquals("22/11/2026" to "18:00", fechaHoraDeFormulario("22112026", "18:00"))
        assertEquals("05/01/2027" to "09:15", fechaHoraDeFormulario("05/01/2027", "09:15"))
    }

    @Test fun estadoDelFormularioSiempreEnDigitos() {
        assertEquals("22112026", soloDigitos("22/11/2026"))
        assertEquals("1830", soloDigitos("18:30"))
    }

    @Test fun fechasYHorasImposibles_seRechazan() {
        assertNull(fechaHoraDeFormulario("32112026", "1830"))
        assertNull(fechaHoraDeFormulario("29022027", "1000"))   // 2027 no es bisiesto
        assertNull(fechaHoraDeFormulario("22132026", "1000"))
        assertNull(fechaHoraDeFormulario("22112026", "2500"))
        assertNull(fechaHoraDeFormulario("22112026", "1860"))
        assertNull(fechaHoraDeFormulario("2211202", "1830"))     // incompleta
        assertNull(fechaHoraDeFormulario("22112026", "183"))
        assertNull(fechaHoraDeFormulario("", ""))
    }

    @Test fun bisiestoValido() {
        assertEquals("29/02/2028" to "10:00", fechaHoraDeFormulario("29022028", "1000"))
    }
}
