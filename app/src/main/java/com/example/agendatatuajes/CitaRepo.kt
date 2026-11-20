package com.example.agendatatuajes

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID

/**
 * Único punto por el que la app guarda o borra citas.
 * En UNA transacción de Room: cambia la cita, sube su revisión y deja la operación en la cola para n8n.
 * Si la app se cierra o el móvil se apaga justo después, no se pierde nada: la operación ya está guardada.
 */
object CitaRepo {

    /** Crea o actualiza una cita. Devuelve la cita tal como ha quedado guardada (con su revisión nueva). */
    suspend fun guardar(context: Context, cita: Cita): Cita {
        val db = AppDatabase.getDatabase(context)
        val guardada = db.withTransaction {
            // Nunca hacia atrás: también se tiene en cuenta una operación pendiente (p. ej. un borrado aún sin enviar)
            val revision = maxOf(db.citaDao().revisionDe(cita.id) ?: 0L, db.syncDao().deCita(cita.id)?.revision ?: 0L) + 1
            val c = cita.copy(revision = revision, sincronizado = false)
            db.citaDao().insertarCita(c)
            // Sustituye cualquier operación pendiente de esta cita (y la desbloquea si estaba bloqueada).
            db.syncDao().encolar(
                OperacionSync(c.id, UUID.randomUUID().toString(), TipoOp.GUARDAR, revision, "", 0, null, false, System.currentTimeMillis())
            )
            c
        }
        // Avisos siempre al día: si la cita se terminó o el cliente la canceló se quitan; si no, se reprograman
        programarAlarmaLocal(context, guardada, pedirPermiso = false)
        SyncN8n.solicitar(context)
        return guardada
    }

    /**
     * Borra una cita del móvil y deja el aviso de borrado en la cola, todo en una transacción.
     * También se avisa al borrar una cita TERMINADA: n8n la deja como "terminada" (no la cancela), pero así
     * el servidor sabe que el último cambio fue un borrado y ningún guardado antiguo pendiente la pisa.
     */
    suspend fun borrar(context: Context, cita: Cita) {
        val db = AppDatabase.getDatabase(context)
        db.withTransaction {
            val actual = db.citaDao().obtenerPorId(cita.id) ?: cita
            val pendiente = db.syncDao().deCita(cita.id)
            val revision = maxOf(actual.revision, pendiente?.revision ?: 0L) + 1
            db.syncDao().encolar(
                OperacionSync(actual.id, UUID.randomUUID().toString(), TipoOp.BORRAR, revision,
                    SyncN8n.datosCita(actual).toString(), 0, null, false, System.currentTimeMillis())
            )
            db.citaDao().borrarCita(actual)
        }
        cancelarAlarma(context, cita) // antes el aviso de una cita borrada seguía sonando
        SyncN8n.solicitar(context)
    }
}
