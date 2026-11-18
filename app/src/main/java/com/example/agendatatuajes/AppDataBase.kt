package com.example.agendatatuajes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Cita::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun citaDao(): CitaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 6 → 7: añade el correo del cliente, la marca de "enviada a n8n" y lo que ha respondido el cliente
         * SIN borrar las citas que ya existen.
         * Las citas antiguas quedan como no sincronizadas y se envían a n8n al abrir la app.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE citas_tatuajes ADD COLUMN email TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE citas_tatuajes ADD COLUMN sincronizado INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE citas_tatuajes ADD COLUMN estadoCliente TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 7 → 8: precio por hora y precio fijo guardados en cada cita, sin borrar nada. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE citas_tatuajes ADD COLUMN tarifaHora TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE citas_tatuajes ADD COLUMN totalFijo TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agenda_tatuajes_db"
                )
                    // ⚠️ IMPORTANTE: cada vez que cambies la clase Cita, sube la versión y añade
                    // aquí su Migration. Sin migración, la línea de abajo BORRA todas las citas.
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
