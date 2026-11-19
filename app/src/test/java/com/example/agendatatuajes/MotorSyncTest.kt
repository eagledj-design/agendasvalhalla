package com.example.agendatatuajes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pruebas del motor de sincronización: desconexión, borrados, reintentos, cambios simultáneos,
 * respuestas retrasadas, idempotencia y errores de configuración.
 * FakeCola reproduce la semántica de SyncDao/CitaDao (una operación por cita, completar condicional).
 * FakeServidor reproduce la semántica de public.sync_cita (solo aplica revisiones mayores).
 */
class MotorSyncTest {

    // ---------- dobles de prueba ----------
    data class CitaLocal(val id: String, var nombre: String, var revision: Long, var sincronizada: Boolean)

    class FakeCola : ColaSync {
        val citas = LinkedHashMap<String, CitaLocal>()
        val ops = LinkedHashMap<String, OpSync>()
        private var reloj = 0L

        @Synchronized fun guardar(id: String, nombre: String): Long {
            val rev = (citas[id]?.revision ?: 0L) + 1
            citas[id] = CitaLocal(id, nombre, rev, sincronizada = false)
            ops[id] = OpSync(id, UUID.randomUUID().toString(), TipoOp.GUARDAR, rev, creadaEn = reloj++)
            return rev
        }

        @Synchronized fun borrar(id: String) {
            val rev = (citas[id]?.revision ?: ops[id]?.revision ?: 0L) + 1
            ops[id] = OpSync(id, UUID.randomUUID().toString(), TipoOp.BORRAR, rev, payload = "{\"id_cita\":\"$id\"}", creadaEn = reloj++)
            citas.remove(id)
        }

        override suspend fun pendientes() = synchronized(this) { ops.values.filter { !it.bloqueada }.sortedBy { it.creadaEn } }

        override suspend fun completar(op: OpSync): Boolean = synchronized(this) {
            if (ops[op.idCita]?.opId != op.opId) return false
            ops.remove(op.idCita)
            if (op.tipo == TipoOp.GUARDAR) citas[op.idCita]?.let { if (it.revision == op.revision) it.sincronizada = true }
            true
        }

        override suspend fun descartar(op: OpSync) = synchronized(this) {
            if (ops[op.idCita]?.opId == op.opId) ops.remove(op.idCita)
            Unit
        }

        override suspend fun registrarFallo(op: OpSync, motivo: String, bloquear: Boolean) = synchronized(this) {
            val actual = ops[op.idCita] ?: return
            if (actual.opId == op.opId) ops[op.idCita] = actual.copy(intentos = actual.intentos + 1, ultimoError = motivo, bloqueada = bloquear)
        }
    }

    data class FilaServidor(var revision: Long, var estado: String, var nombre: String, var aplicaciones: Int = 0)

    class FakeServidor {
        val filas = HashMap<String, FilaServidor>()
        @Volatile var online = true
        @Volatile var codigoForzado: Int? = null       // simula 400/401/500...
        @Volatile var sinConfirmar = false             // simula n8n antiguo: 200 sin {ok:true}
        @Volatile var perderRespuesta = false          // aplica en BD pero la respuesta no llega
        var alRecibir: (() -> Unit)? = null            // se ejecuta antes de responder (respuesta retrasada)

        @Synchronized fun aplicar(op: OpSync, nombre: String): Long {
            val f = filas[op.idCita]
            if (f == null || op.revision == 0L || f.revision < op.revision) {
                val estado = if (op.tipo == TipoOp.BORRAR) "cancelada" else "pendiente"
                val nueva = FilaServidor(op.revision, estado, nombre, (f?.aplicaciones ?: 0) + 1)
                filas[op.idCita] = nueva
                return nueva.revision
            }
            return f.revision
        }

        fun recibir(op: OpSync, nombre: String): RespuestaN8n {
            if (!online) throw IOException("sin red")
            codigoForzado?.let { return RespuestaN8n(it, ok = false, error = "forzado") }
            val rev = aplicar(op, nombre)
            alRecibir?.invoke()
            if (perderRespuesta) { perderRespuesta = false; throw IOException("se cortó la respuesta") }
            if (sinConfirmar) return RespuestaN8n(200)
            return RespuestaN8n(200, ok = true, idCita = op.idCita, opId = op.opId, revisionServidor = rev)
        }
    }

    class FakeTransporte(private val cola: FakeCola, private val srv: FakeServidor) : TransporteSync {
        val enCurso = AtomicInteger(0)
        val maxSimultaneos = AtomicInteger(0)
        val llamadas = AtomicInteger(0)
        var espera = 0L
        override suspend fun enviar(op: OpSync): Envio {
            llamadas.incrementAndGet()
            val n = enCurso.incrementAndGet()
            maxSimultaneos.updateAndGet { maxOf(it, n) }
            try {
                if (espera > 0) delay(espera)
                val nombre = if (op.tipo == TipoOp.GUARDAR) {
                    val c = synchronized(cola) { cola.citas[op.idCita]?.copy() } ?: return Envio.SinDatos
                    if (c.revision != op.revision) return Envio.Obsoleta
                    c.nombre
                } else ""
                return Envio.Enviada(srv.recibir(op, nombre))
            } finally {
                enCurso.decrementAndGet()
            }
        }
    }

    private fun motor(cola: FakeCola, t: TransporteSync, config: String? = null) =
        MotorSync(cola, t, { config }, kotlinx.coroutines.sync.Mutex())

    // ---------- desconexión y reintentos ----------
    @Test fun sinConexion_laOperacionSeConservaYSeEnviaAlVolver() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana")
        srv.online = false
        assertEquals(EstadoSync.PENDIENTE, motor(cola, t).procesar())
        assertEquals(1, cola.ops["c1"]!!.intentos)
        assertFalse(cola.citas["c1"]!!.sincronizada)
        assertTrue(srv.filas.isEmpty())
        srv.online = true
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())
        assertTrue(cola.ops.isEmpty())
        assertTrue(cola.citas["c1"]!!.sincronizada)
        assertEquals(1L, srv.filas["c1"]!!.revision)
    }

    @Test fun error500_esTransitorio() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana"); srv.codigoForzado = 500
        assertEquals(EstadoSync.PENDIENTE, motor(cola, t).procesar())
        assertFalse(cola.ops["c1"]!!.bloqueada)
        srv.codigoForzado = null
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())
    }

    @Test fun webhookInactivo404_esTransitorio() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana"); srv.codigoForzado = 404
        assertEquals(EstadoSync.PENDIENTE, motor(cola, t).procesar())
        assertNotNull(cola.ops["c1"])
    }

    // ---------- borrados ----------
    @Test fun borrarSinConexion_noSePierdeYCancelaEnServidor() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana")
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())
        srv.online = false
        cola.borrar("c1")
        assertNull(cola.citas["c1"])
        assertEquals(EstadoSync.PENDIENTE, motor(cola, t).procesar())
        assertEquals(TipoOp.BORRAR, cola.ops["c1"]!!.tipo)
        srv.online = true
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())
        assertEquals("cancelada", srv.filas["c1"]!!.estado)
        assertEquals(2L, srv.filas["c1"]!!.revision)
        assertTrue(cola.ops.isEmpty())
    }

    @Test fun guardarPendienteYDespuesBorrar_soloViajaElBorrado() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        srv.online = false
        cola.guardar("c1", "Ana")
        cola.borrar("c1")
        assertEquals(1, cola.ops.size)
        assertEquals(TipoOp.BORRAR, cola.ops["c1"]!!.tipo)
        srv.online = true
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())
        assertEquals("cancelada", srv.filas["c1"]!!.estado)
        assertEquals(1, srv.filas["c1"]!!.aplicaciones)
    }

    @Test fun guardadoRetrasadoNoResucitaUnaCitaBorrada() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor()
        val viejo = OpSync("c1", "op-viejo", TipoOp.GUARDAR, 1)
        val borrado = OpSync("c1", "op-borrar", TipoOp.BORRAR, 2)
        srv.recibir(borrado, "")
        val r = srv.recibir(viejo, "Ana")             // llega tarde
        assertEquals("cancelada", srv.filas["c1"]!!.estado)
        assertEquals(Clasificacion.Exito, clasificarRespuesta(viejo, r)) // idempotente: no se reintenta en bucle
        assertTrue(cola.ops.isEmpty())
    }

    @Test fun guardadoHuerfano_seDescarta() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.ops["x"] = OpSync("x", "op", TipoOp.GUARDAR, 1)   // sin cita local (no debería pasar)
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())
        assertTrue(cola.ops.isEmpty())
        assertTrue(srv.filas.isEmpty())
    }

    // ---------- cambios simultáneos y respuestas retrasadas ----------
    @Test fun edicionDuranteElEnvio_noMarcaComoSincronizadaLaVersionNueva() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana")
        var editado = false
        srv.alRecibir = { if (!editado) { editado = true; cola.guardar("c1", "Ana María") } }
        val estado = motor(cola, t).procesar()
        assertEquals(EstadoSync.AL_DIA, estado)                 // en la 2ª pasada envía la versión nueva
        assertEquals(2L, srv.filas["c1"]!!.revision)
        assertEquals("Ana María", srv.filas["c1"]!!.nombre)
        assertTrue(cola.citas["c1"]!!.sincronizada)
        assertTrue(cola.ops.isEmpty())
    }

    @Test fun completarConOperacionSustituida_devuelveFalseYNoMarca() = runBlocking {
        val cola = FakeCola()
        cola.guardar("c1", "Ana")
        val op1 = cola.ops["c1"]!!
        cola.guardar("c1", "Ana María")                         // llega una edición antes de la respuesta
        assertFalse(cola.completar(op1))
        assertFalse(cola.citas["c1"]!!.sincronizada)
        assertEquals(2L, cola.ops["c1"]!!.revision)
    }

    @Test fun respuestaConRevisionMenor_noSeDaPorBuena() {
        val op = OpSync("c1", "op2", TipoOp.GUARDAR, 2)
        val r = RespuestaN8n(200, ok = true, idCita = "c1", opId = "op2", revisionServidor = 1)
        assertTrue(clasificarRespuesta(op, r) is Clasificacion.Transitorio)
    }

    @Test fun muchasEdicionesMientrasSeSincroniza_acabaConsistente() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv); t.espera = 2
        val m = MotorSync(cola, t, { null }, kotlinx.coroutines.sync.Mutex())
        withContext(Dispatchers.Default) {
            val editor = launch { repeat(30) { i -> cola.guardar("c${i % 3}", "v$i"); delay(1) } }
            val sync = launch { repeat(40) { m.procesar(); delay(1) } }
            editor.join(); sync.join()
        }
        while (m.procesar() != EstadoSync.AL_DIA) { /* vacía lo último */ }
        for (id in listOf("c0", "c1", "c2")) {
            val local = cola.citas[id]!!
            assertEquals("revisión de $id", local.revision, srv.filas[id]!!.revision)
            assertEquals("datos de $id", local.nombre, srv.filas[id]!!.nombre)
            assertTrue(local.sincronizada)
        }
        assertTrue(cola.ops.isEmpty())
    }

    @Test fun nuncaHayDosEnviosALaVez() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv); t.espera = 20
        repeat(5) { cola.guardar("c$it", "n$it") }
        val m1 = MotorSync(cola, t, { null })     // mutex global compartido, como la app y el Worker
        val m2 = MotorSync(cola, t, { null })
        withContext(Dispatchers.Default) {
            listOf(async { m1.procesar() }, async { m2.procesar() }, async { m1.procesar() }).awaitAll()
        }
        assertEquals(1, t.maxSimultaneos.get())
        assertTrue(cola.ops.isEmpty())
    }

    // ---------- idempotencia ----------
    @Test fun respuestaPerdida_alReintentarNoSeDuplica() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana")
        srv.perderRespuesta = true
        assertEquals(EstadoSync.PENDIENTE, motor(cola, t).procesar())   // el servidor SÍ lo guardó
        assertEquals(1, srv.filas["c1"]!!.aplicaciones)
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())      // reintento: el servidor no lo reaplica
        assertEquals(1, srv.filas["c1"]!!.aplicaciones)
        assertTrue(cola.citas["c1"]!!.sincronizada)
    }

    // ---------- confirmación explícita de n8n ----------
    @Test fun n8nSinConfirmacion_noSeDaPorHecho() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana"); srv.sinConfirmar = true
        assertEquals(EstadoSync.PENDIENTE, motor(cola, t).procesar())
        assertNotNull(cola.ops["c1"])
        assertFalse(cola.citas["c1"]!!.sincronizada)
    }

    @Test fun respuestaDeOtraCitaUOperacion_noSeDaPorBuena() {
        val op = OpSync("c1", "op1", TipoOp.GUARDAR, 1)
        assertTrue(clasificarRespuesta(op, RespuestaN8n(200, true, "c2", "op1", 1)) is Clasificacion.Transitorio)
        assertTrue(clasificarRespuesta(op, RespuestaN8n(200, true, "c1", "otra", 1)) is Clasificacion.Transitorio)
        assertTrue(clasificarRespuesta(op, RespuestaN8n(200, false, "c1", "op1", 1)) is Clasificacion.Transitorio)
        assertTrue(clasificarRespuesta(op, RespuestaN8n(200, null, "c1", "op1", 1)) is Clasificacion.Transitorio)
        assertEquals(Clasificacion.Exito, clasificarRespuesta(op, RespuestaN8n(200, true, "c1", "op1", 1)))
        assertEquals(Clasificacion.Exito, clasificarRespuesta(op, RespuestaN8n(200, true, "c1", null, 3)))
    }

    // ---------- errores permanentes ----------
    @Test fun error400_bloqueaSoloEsaOperacionYSigueConLasDemas() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor()
        cola.guardar("mala", "X"); cola.guardar("buena", "Y")
        val t = TransporteSync { op ->
            if (op.idCita == "mala") Envio.Enviada(RespuestaN8n(400, false, error = "fecha_no_valida"))
            else Envio.Enviada(srv.recibir(op, "Y"))
        }
        assertEquals(EstadoSync.AL_DIA, motor(cola, t).procesar())
        assertTrue(cola.ops["mala"]!!.bloqueada)                 // no se pierde: queda guardada y bloqueada
        assertNull(cola.ops["buena"])
        cola.guardar("mala", "X corregida")                       // al editarla, se desbloquea sola
        assertFalse(cola.ops["mala"]!!.bloqueada)
    }

    @Test fun error401_esDeConfiguracionYNoBloquea() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana"); srv.codigoForzado = 401
        assertEquals(EstadoSync.ERROR_CONFIG, motor(cola, t).procesar())
        assertFalse(cola.ops["c1"]!!.bloqueada)
    }

    @Test fun configIncompleta_noEnviaNada() = runBlocking {
        val cola = FakeCola(); val srv = FakeServidor(); val t = FakeTransporte(cola, srv)
        cola.guardar("c1", "Ana")
        assertEquals(EstadoSync.ERROR_CONFIG, motor(cola, t, config = "falta la api key").procesar())
        assertEquals(0, t.llamadas.get())
        assertNotNull(cola.ops["c1"])
    }

    @Test fun validarConfig() {
        val url = "https://raslinux.tail088b3f.ts.net"
        val key = "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
        assertNull(validarConfigSync(url, "ES", "ES0001", key))
        assertNull(validarConfigSync(url, "co", "CO0003", key))
        assertNotNull(validarConfigSync(url, "ES", "ES0001", ""))
        assertNotNull(validarConfigSync(url, "ES", "ES0001", "   "))
        assertNotNull(validarConfigSync(url, "ES", "ES0001", "PON_AQUI_LA_API_KEY"))
        assertNotNull(validarConfigSync(url, "ES", "ES0001", "TU_API_KEY_AGENDA_ES"))
        assertNotNull(validarConfigSync(url, "ES", "ES0001", "corta"))
        assertNotNull(validarConfigSync(url, "ES", "ES0001", "clave con espacios 1234567890"))
        assertNotNull(validarConfigSync(url, "ES", "PON_AQUI_ID", key))
        assertNotNull(validarConfigSync(url, "ES", "", key))
        assertNotNull(validarConfigSync("$url/webhook", "ES", "ES0001", key))
        assertNotNull(validarConfigSync("$url/", "ES", "ES0001", key))
        assertNotNull(validarConfigSync("http://raslinux.tail088b3f.ts.net", "ES", "ES0001", key))
        assertNotNull(validarConfigSync(url, "MX", "ES0001", key))
    }
}
