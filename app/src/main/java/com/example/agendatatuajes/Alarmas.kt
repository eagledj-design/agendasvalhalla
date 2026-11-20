package com.example.agendatatuajes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/*
 * Avisos a la profesional antes de cada cita (alarmas del propio móvil).
 *
 * - programarAlarmaLocal: (re)programa los dos avisos de una cita. Si la cita está terminada o el cliente
 *   la canceló, solo los quita. Sin permiso de "alarmas exactas" se programan igual (pueden llegar con
 *   unos minutos de margen) en vez de no programarse, que es lo que pasaba antes.
 * - Android borra todas las alarmas al reiniciar el móvil (y algunas marcas, como Xiaomi, al cerrar la app
 *   a la fuerza). Por eso se vuelven a programar todas al encender el móvil, al actualizar la app,
 *   al cambiar la hora del móvil y cada vez que se abre la agenda.
 */
private const val TAG = "Alarmas"
private var permisoPedidoEstaSesion = false

fun programarAlarmaLocal(context: Context, cita: Cita, pedirPermiso: Boolean = true) {
    try {
        cancelarAlarma(context, cita)
        if (cita.terminado || cita.estadoCliente == "cancelada") return
        val avisos = avisosDeCita(cita.id, cita.fecha, cita.hora, cita.recordatorio, System.currentTimeMillis())
        if (avisos.isEmpty()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactas = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (!exactas && pedirPermiso && !permisoPedidoEstaSesion) {
            permisoPedidoEstaSesion = true
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir el ajuste de alarmas exactas", e)
            }
        }
        for (a in avisos) {
            val intent = Intent(context, NotificacionReceiver::class.java)
                .putExtra("CLIENTE", cita.cliente)
                .putExtra("HORA", cita.hora)
                .putExtra("TIEMPO_RESTANTE", a.antelacion)
            val pi = PendingIntent.getBroadcast(context, a.codigo, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (exactas) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.enMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.enMillis, pi)
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudieron programar los avisos de ${cita.id}", e)
    }
}

fun cancelarAlarma(context: Context, cita: Cita) {
    try {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (codigo in codigosAviso(cita.id)) {
            val pi = PendingIntent.getBroadcast(
                context, codigo, Intent(context, NotificacionReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            am.cancel(pi)
            pi.cancel()
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudieron cancelar los avisos de ${cita.id}", e)
    }
}

/** Vuelve a programar los avisos de todas las citas pendientes (sin abrir ninguna pantalla de ajustes). */
suspend fun reprogramarTodas(context: Context) {
    val citas = AppDatabase.getDatabase(context).citaDao().pendientesParaAvisos()
    for (c in citas) programarAlarmaLocal(context, c, pedirPermiso = false)
    Log.i(TAG, "Avisos reprogramados: ${citas.size} citas pendientes")
}

/** Al encender el móvil, al actualizar la app, al cambiar la hora o al dar el permiso de alarmas exactas. */
class ReprogramarAlarmasReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendiente = goAsync()
        val app = context.applicationContext
        Moneda.fijar(app.getString(R.string.agenda_pais))
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reprogramarTodas(app)
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al reprogramar (${intent.action})", e)
            } finally {
                pendiente.finish()
            }
        }
    }
}
