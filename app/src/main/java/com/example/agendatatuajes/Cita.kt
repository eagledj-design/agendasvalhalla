package com.example.agendatatuajes

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "citas_tatuajes")
data class Cita(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val cliente: String,
    val telefono: String,
    val fecha: String,
    val hora: String,
    val direccion: String,
    val horas: String,
    val notas: String,
    val terminado: Boolean = false,
    val abonado: String = "0",
    val pendiente: String = "",
    val fotoPath: String? = null,
    val recordatorio: Int = 2,
    // ── Nuevo en la versión 7 (conexión con n8n) ──
    // (sin @ColumnInfo(defaultValue): así Room no compara el DEFAULT de la migración y no falla)
    val email: String = "",
    val sincronizado: Boolean = false,
    // "", "pendiente", "confirmada" o "cancelada": lo que ha respondido el cliente (viene de n8n)
    val estadoCliente: String = "",
    // ── Nuevo en la versión 8 ──
    // Precio por hora con el que se calculó esta cita ("" en citas antiguas → se usa el de Ajustes)
    val tarifaHora: String = "",
    // Precio cerrado de la cita (modo "precio fijo"). Si tiene valor, el total es este y no horas × precio/hora.
    val totalFijo: String = "",
    // ── Nuevo en la versión 9 ──
    // "dd/MM/yyyy" del día en que la cita quedó terminada y pagada del todo ("" si aún no).
    // La pestaña de Ganancias cuenta cada cita en el mes de esta fecha.
    val fechaCobro: String = ""
)
