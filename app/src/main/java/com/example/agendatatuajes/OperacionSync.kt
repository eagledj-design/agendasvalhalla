package com.example.agendatatuajes

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Cola persistente de operaciones pendientes de enviar a n8n (sustituye a la cola de SharedPreferences).
 *
 * Una fila por cita como máximo (clave = idCita): si se vuelve a editar o se borra una cita que ya tenía
 * algo pendiente, la operación nueva SUSTITUYE a la anterior (REPLACE), con un opId nuevo y su revisión.
 * Así un borrado nunca convive con un guardado pendiente de la misma cita.
 *
 *  - tipo: "guardar" o "borrar"
 *  - revision: revisión de la cita que representa esta operación
 *  - payload: solo para "borrar" (la cita ya no existe en el móvil): copia de los datos necesarios en JSON.
 *             Para "guardar" va vacío: se envía la cita tal como está en ese momento.
 *  - bloqueada: n8n rechazó la operación por un error permanente (400/409). No se reintenta en bucle,
 *               pero NO se borra; se desbloquea sola si la cita se vuelve a editar.
 */
@Entity(tableName = "operaciones_sync")
data class OperacionSync(
    @PrimaryKey val idCita: String,
    val opId: String,
    val tipo: String,
    val revision: Long,
    val payload: String,
    val intentos: Int,
    val ultimoError: String?,
    val bloqueada: Boolean,
    val creadaEn: Long
) {
    fun aOp() = OpSync(idCita, opId, tipo, revision, payload, intentos, ultimoError, bloqueada, creadaEn)
}

@Dao
interface SyncDao {
    /** Encola (o sustituye la operación pendiente de esa cita). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun encolar(op: OperacionSync)

    @Query("SELECT * FROM operaciones_sync WHERE bloqueada = 0 ORDER BY creadaEn ASC")
    suspend fun pendientes(): List<OperacionSync>

    @Query("SELECT * FROM operaciones_sync WHERE idCita = :idCita LIMIT 1")
    suspend fun deCita(idCita: String): OperacionSync?

    /** Quita la operación SOLO si sigue siendo la misma (no la sustituyó otra más nueva). Devuelve filas borradas. */
    @Query("DELETE FROM operaciones_sync WHERE idCita = :idCita AND opId = :opId")
    suspend fun borrarSiOp(idCita: String, opId: String): Int

    @Query("UPDATE operaciones_sync SET intentos = intentos + 1, ultimoError = :error, bloqueada = :bloquear WHERE idCita = :idCita AND opId = :opId")
    suspend fun registrarFallo(idCita: String, opId: String, error: String, bloquear: Boolean): Int

    /** Al abrir la app: vuelve a intentar las rechazadas que llevan menos de :max intentos (p. ej. si se corrigió el servidor). */
    @Query("UPDATE operaciones_sync SET bloqueada = 0 WHERE bloqueada = 1 AND intentos < :max")
    suspend fun reintentarBloqueadas(max: Int): Int

    @Query("SELECT COUNT(*) FROM operaciones_sync")
    suspend fun contar(): Int

    @Query("SELECT COUNT(*) FROM operaciones_sync WHERE bloqueada = 1")
    suspend fun contarBloqueadas(): Int
}
