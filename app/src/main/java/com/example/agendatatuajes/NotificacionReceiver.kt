package com.example.agendatatuajes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificacionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cliente = intent.getStringExtra("CLIENTE") ?: "Cliente"
        val hora = intent.getStringExtra("HORA") ?: ""
        val tiempoRestante = intent.getStringExtra("TIEMPO_RESTANTE") ?: "unas horas"
        val servicio = context.getString(R.string.agenda_servicio).trim().lowercase()
        val texto = "Tienes ${if (servicio.isNotEmpty()) "$servicio con " else "cita con "}$cliente en $tiempoRestante (a las $hora)"

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "citas_tatuajes_channel"

        // En Android 8.0 o superior es obligatorio crear un canal de notificación
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Avisos de citas",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        // Construimos la alerta visual en el teléfono
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm) // Icono nativo de alarma
            .setContentTitle("⏰ ${context.getString(R.string.app_name)}")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent( // al tocar el aviso se abre la agenda
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        // Lanzamos la notificación en pantalla usando un ID único basado en el tiempo
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
