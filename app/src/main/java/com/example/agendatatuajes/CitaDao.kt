package com.example.agendatatuajes

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CitaDao {
    @Query("SELECT * FROM citas_tatuajes WHERE terminado = 0 ORDER BY fecha ASC, hora ASC")
    fun obtenerCitasPendientes(): Flow<List<Cita>>

    @Query("SELECT * FROM citas_tatuajes WHERE terminado = 1 ORDER BY fecha DESC")
    fun obtenerCitasTerminadas(): Flow<List<Cita>>

    @Query("SELECT * FROM citas_tatuajes WHERE terminado = 1 AND pendiente != '' AND pendiente != '0' AND pendiente != '0.0' ORDER BY fecha DESC")
    fun obtenerCobrosPendientes(): Flow<List<Cita>>

    @Query("SELECT * FROM citas_tatuajes WHERE fotoPath IS NOT NULL ORDER BY fecha DESC")
    fun obtenerGaleria(): Flow<List<Cita>>

    // Ganancias: solo citas terminadas y pagadas del todo (tienen fecha de cobro)
    @Query("SELECT * FROM citas_tatuajes WHERE fechaCobro != '' ORDER BY substr(fechaCobro, 7, 4) || substr(fechaCobro, 4, 2) || substr(fechaCobro, 1, 2) DESC")
    fun obtenerGanancias(): Flow<List<Cita>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarCita(cita: Cita)

    @Delete
    suspend fun borrarCita(cita: Cita)

    // ── Sincronización con n8n ──
    @Query("SELECT * FROM citas_tatuajes WHERE sincronizado = 0")
    suspend fun obtenerNoSincronizadas(): List<Cita>

    @Query("UPDATE citas_tatuajes SET sincronizado = 1 WHERE id = :id")
    suspend fun marcarSincronizada(id: String)

    @Query("SELECT * FROM citas_tatuajes WHERE id = :id LIMIT 1")
    suspend fun obtenerPorId(id: String): Cita?

    @Query("UPDATE citas_tatuajes SET estadoCliente = :estado WHERE id = :id")
    suspend fun actualizarEstadoCliente(id: String, estado: String)
}
