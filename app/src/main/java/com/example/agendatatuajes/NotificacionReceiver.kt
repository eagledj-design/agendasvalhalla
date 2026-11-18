package com.example.agendatatuajes

import android.app.NotificationChannel
import android.app.NotificationManager
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
        val infoLimpieza = "¡Recordatorio! Tienes una limpieza con $cliente en $tiempoRestante (a las $hora)"

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "citas_tatuajes_channel"

        // En Android 8.0 o superior es obligatorio crear un canal de notificación
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Recordatorios de Limpieza",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        // Construimos la alerta visual en el teléfono
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm) // Icono nativo de alarma
            .setContentTitle("Agenda 111Clean - ¡Cita de Limpieza!")
            .setContentText(infoLimpieza)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Lanzamos la notificación en pantalla usando un ID único basado en el tiempo
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
