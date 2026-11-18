package com.example.agendatatuajes

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Gasto de la profesional (material, transporte...). Solo vive en el móvil:
 * no se envía a n8n. Sirve para la pestaña $ → Gastos y el beneficio del mes.
 */
@Entity(tableName = "gastos")
data class Gasto(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val concepto: String,
    val importe: String,      // "12.5" (punto decimal, como en las citas)
    val fecha: String,        // "dd/MM/yyyy"
    val categoria: String = "Material"
)

val CATEGORIAS_GASTO = listOf("Material", "Productos", "Herramientas", "Transporte", "Local / alquiler", "Publicidad", "Otros")

@Dao
interface GastoDao {
    @Query("SELECT * FROM gastos ORDER BY substr(fecha, 7, 4) || substr(fecha, 4, 2) || substr(fecha, 1, 2) DESC")
    fun obtenerTodos(): Flow<List<Gasto>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(gasto: Gasto)

    @Delete
    suspend fun borrar(gasto: Gasto)
}
