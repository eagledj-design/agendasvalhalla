package com.example.agendatatuajes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Moneda según el país de la agenda: € en España, pesos en Colombia. */
class ImportesTest {
    @After fun volverAEspana() = Moneda.fijar("ES")

    @Test fun espana_formatoEuros() {
        Moneda.fijar("ES")
        assertEquals("36,50 €", dinero(36.5))
        assertEquals("45 €", dinero(45.0))
        assertEquals("1500 €", dinero(1500.0))
        assertEquals("− 12,30 €", dinero(-12.3))
        assertEquals("0 €", dinero(-0.001))
        assertEquals("Abonado (€)", conMoneda("Abonado"))
    }

    @Test fun colombia_formatoPesos() {
        Moneda.fijar("CO")
        assertEquals("$150.000", dinero(150000.0))
        assertEquals("$1.500.000", dinero(1_500_000.0))
        assertEquals("$950", dinero(950.0))
        assertEquals("$15.001", dinero(15000.6))
        assertEquals("− $20.000", dinero(-20000.0))
        assertEquals("Abonado ($)", conMoneda("Abonado"))
        assertEquals("150.000", mostrarNum(150000.0))
    }

    @Test fun paisDesconocido_esEspana() {
        Moneda.fijar("co "); assertEquals("CO", Moneda.pais)
        Moneda.fijar("xx"); assertEquals("ES", Moneda.pais)
        Moneda.fijar(null); assertEquals("€", Moneda.simbolo)
    }

    @Test fun leerLoQueEscribe_espana() {
        Moneda.fijar("ES")
        assertEquals(12.5, num("12,5")!!, 1e-9)
        assertEquals(12.5, num("12.5")!!, 1e-9)
        assertEquals(1500.0, num("1.500")!!, 1e-9)
        assertEquals(1500.5, num("1.500,50")!!, 1e-9)
        assertEquals(36.0, num(" 36 € ")!!, 1e-9)
        assertEquals(1.5, num("1,500")!!, 1e-9)       // en España la coma es decimal
        assertNull(num("")); assertNull(num("abc")); assertNull(num(null))
    }

    @Test fun leerLoQueEscribe_colombia() {
        Moneda.fijar("CO")
        assertEquals(150000.0, num("150.000")!!, 1e-9)
        assertEquals(150000.0, num("$150.000")!!, 1e-9)
        assertEquals(150000.0, num("150,000")!!, 1e-9)  // en pesos no hay céntimos: son miles
        assertEquals(1500000.0, num("1.500.000")!!, 1e-9)
        assertEquals(150000.0, num("150000")!!, 1e-9)
    }

    @Test fun loGuardadoSeLeeIgualEnLosDosPaises() {
        for (pais in listOf("ES", "CO")) {
            Moneda.fijar(pais)
            for (v in listOf(0.0, 36.5, 36.75, 150000.0, 12.0, 0.13)) {
                assertEquals("$pais $v", v, num(guardarNum(v))!!, 1e-9)
            }
        }
    }

    @Test fun loQueSeMuestraEnUnCampoSeVuelveALeerIgual() {
        Moneda.fijar("CO")
        for (v in listOf(950.0, 15000.0, 150000.0, 2_350_000.0)) assertEquals(v, num(mostrarNum(v))!!, 1e-9)
        Moneda.fijar("ES")
        for (v in listOf(9.5, 15.0, 1500.0, 36.75)) assertEquals(v, num(mostrarNum(v))!!, 1e-9)
    }
}
