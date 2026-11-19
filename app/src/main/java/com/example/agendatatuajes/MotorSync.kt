package com.example.agendatatuajes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Motor de sincronización con n8n. Kotlin puro (sin Android) para poder probarlo en la JVM.
 *
 *  · Cola persistente: una operación pendiente como máximo por cita (la más nueva sustituye a la anterior,
 *    y un borrado sustituye a un guardado pendiente). Ver OperacionSync / SyncDao.
 *  · Cada operación lleva la REVISIÓN de la cita y un op_id único. n8n/Supabase solo aplican una revisión
 *    mayor que la guardada, así que repetir una operación o recibirla tarde no pisa nada (idempotente).
 *  · Una operación solo se da por hecha si n8n contesta {ok:true} con el mismo id_cita (y op_id) y una
 *    revisión del servidor >= la enviada. Un 200 sin esa confirmación cuenta como fallo transitorio.
 *  · Fallos transitorios (sin red, 5xx, 429, 404 de webhook inactivo) → se reintenta más tarde.
 *    Fallos permanentes de la operación (400/409/422) → la operación queda BLOQUEADA en la cola (no se
 *    pierde) y se desbloquea sola si la cita se vuelve a editar.
 *    Fallos de configuración (config.xml incompleto, 401/403) → se para y no se reintenta en bucle.
 *  · Un único Mutex global: nunca hay dos envíos a la vez, así que nunca salen fuera de orden.
 */

object TipoOp {
    const val GUARDAR = "guardar"
    const val BORRAR = "borrar"
}

/** Operación pendiente tal como la ve el motor (independiente de Room). */
data class OpSync(
    val idCita: String,
    val opId: String,
    val tipo: String,
    val revision: Long,
    val payload: String = "",
    val intentos: Int = 0,
    val ultimoError: String? = null,
    val bloqueada: Boolean = false,
    val creadaEn: Long = 0
)

/** Respuesta de n8n ya interpretada (código HTTP + campos del JSON). */
data class RespuestaN8n(
    val codigoHttp: Int,
    val ok: Boolean? = null,
    val idCita: String? = null,
    val opId: String? = null,
    val revisionServidor: Long? = null,
    val error: String? = null
)

/** Resultado de intentar enviar una operación. */
sealed class Envio {
    data class Enviada(val respuesta: RespuestaN8n) : Envio()
    /** La cita ha cambiado desde que se leyó la cola: hay otra operación más nueva. */
    object Obsoleta : Envio()
    /** No hay nada que enviar (la cita ya no existe y no hay borrado pendiente). Se descarta la operación. */
    object SinDatos : Envio()
}

sealed class Clasificacion {
    object Exito : Clasificacion()
    data class Transitorio(val motivo: String) : Clasificacion()
    data class PermanenteOperacion(val motivo: String) : Clasificacion()
    data class PermanenteConfig(val motivo: String) : Clasificacion()
}

enum class EstadoSync { AL_DIA, PENDIENTE, ERROR_CONFIG }

interface ColaSync {
    /** Operaciones NO bloqueadas, de la más antigua a la más nueva. */
    suspend fun pendientes(): List<OpSync>
    /**
     * Quita la operación SOLO si sigue siendo la misma (mismo op_id) y, si era un guardado, marca la cita como
     * sincronizada SOLO si sigue en esa revisión. Todo en una transacción. Devuelve false si entretanto
     * apareció una operación más nueva para esa cita (que se enviará en la siguiente pasada).
     */
    suspend fun completar(op: OpSync): Boolean
    /** Quita una operación que ya no tiene datos que enviar (mismo op_id). */
    suspend fun descartar(op: OpSync)
    suspend fun registrarFallo(op: OpSync, motivo: String, bloquear: Boolean)
}

fun interface TransporteSync {
    /** Envía la operación. Lanza una excepción si no hay conexión. */
    suspend fun enviar(op: OpSync): Envio
}

/** Decide si la respuesta de n8n confirma la operación. */
fun clasificarRespuesta(op: OpSync, r: RespuestaN8n): Clasificacion {
    val c = r.codigoHttp
    return when {
        c in 200..299 -> when {
            r.ok != true -> Clasificacion.Transitorio("n8n respondió $c sin confirmar el guardado")
            r.idCita != op.idCita -> Clasificacion.Transitorio("la respuesta es de otra cita (${r.idCita})")
            !r.opId.isNullOrEmpty() && r.opId != op.opId -> Clasificacion.Transitorio("la respuesta es de otra operación")
            op.revision > 0 && (r.revisionServidor == null || r.revisionServidor < op.revision) ->
                Clasificacion.Transitorio("el servidor no confirma la revisión ${op.revision} (tiene ${r.revisionServidor})")
            else -> Clasificacion.Exito
        }
        c == 401 || c == 403 -> Clasificacion.PermanenteConfig("n8n rechaza las credenciales de la agenda: ${r.error ?: c}")
        c == 400 || c == 409 || c == 422 -> Clasificacion.PermanenteOperacion("n8n rechaza la operación ($c): ${r.error ?: ""}")
        c == 404 -> Clasificacion.Transitorio("webhook no disponible (¿flujo desactivado?)")
        c == 408 || c == 429 || c >= 500 -> Clasificacion.Transitorio("n8n respondió $c")
        else -> Clasificacion.PermanenteOperacion("respuesta inesperada $c")
    }
}

/** Comprueba config.xml. Devuelve null si está bien o el motivo del error. */
fun validarConfigSync(baseUrl: String, pais: String, idClienta: String, apiKey: String): String? {
    val base = baseUrl.trim()
    val key = apiKey.trim()
    val id = idClienta.trim()
    return when {
        !Regex("^https://[A-Za-z0-9.-]+(:\\d{1,5})?$").matches(base) ->
            "agenda_base_url debe ser https://dominio, sin barra final ni /webhook"
        pais.trim().lowercase() !in setOf("es", "co") -> "agenda_pais debe ser ES o CO"
        id.isEmpty() || id.startsWith("PON_AQUI") || !Regex("^[A-Za-z0-9_-]{1,40}$").matches(id) ->
            "agenda_id_clienta vacío o sin rellenar"
        key.isEmpty() || key.startsWith("PON_AQUI") || key.startsWith("TU_") -> "agenda_api_key vacía o sin rellenar"
        key.length < 16 || !Regex("^[A-Za-z0-9_-]+$").matches(key) -> "agenda_api_key no tiene un formato válido"
        else -> null
    }
}

class MotorSync(
    private val cola: ColaSync,
    private val transporte: TransporteSync,
    private val errorConfig: () -> String?,
    private val mutex: Mutex = MUTEX_GLOBAL
) {
    companion object {
        /** Compartido por la app y el WorkManager (mismo proceso): un solo envío a la vez. */
        val MUTEX_GLOBAL = Mutex()
    }

    var ultimoMotivo: String? = null
        private set

    suspend fun procesar(maxPasadas: Int = 5): EstadoSync = mutex.withLock {
        errorConfig()?.let { ultimoMotivo = it; return EstadoSync.ERROR_CONFIG }
        repeat(maxPasadas) {
            val ops = cola.pendientes()
            if (ops.isEmpty()) return EstadoSync.AL_DIA
            for (op in ops) {
                val envio = try {
                    transporte.enviar(op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val m = "sin conexión con n8n: ${e.message ?: e.javaClass.simpleName}"
                    cola.registrarFallo(op, m, bloquear = false)
                    ultimoMotivo = m
                    return EstadoSync.PENDIENTE
                }
                when (envio) {
                    Envio.Obsoleta -> continue
                    Envio.SinDatos -> cola.descartar(op)
                    is Envio.Enviada -> when (val c = clasificarRespuesta(op, envio.respuesta)) {
                        Clasificacion.Exito -> cola.completar(op)
                        is Clasificacion.Transitorio -> {
                            cola.registrarFallo(op, c.motivo, bloquear = false)
                            ultimoMotivo = c.motivo
                            return EstadoSync.PENDIENTE
                        }
                        is Clasificacion.PermanenteOperacion -> {
                            cola.registrarFallo(op, c.motivo, bloquear = true)
                            ultimoMotivo = c.motivo
                        }
                        is Clasificacion.PermanenteConfig -> {
                            cola.registrarFallo(op, c.motivo, bloquear = false)
                            ultimoMotivo = c.motivo
                            return EstadoSync.ERROR_CONFIG
                        }
                    }
                }
            }
        }
        // Siguen entrando cambios mientras enviábamos: que se reintente pronto.
        return if (cola.pendientes().isEmpty()) EstadoSync.AL_DIA else EstadoSync.PENDIENTE
    }
}
