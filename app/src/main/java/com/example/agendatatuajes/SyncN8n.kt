package com.example.agendatatuajes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Conexión de la agenda con n8n (webhook /webhook/{pais}/agenda/cita-sync, protocolo 2).
 *
 * Cómo viaja un cambio:
 *  1. CitaRepo guarda/borra la cita y, en LA MISMA transacción de Room, sube su revisión y deja la
 *     operación en la cola persistente (tabla operaciones_sync). Si se cierra la app o se va la luz
 *     justo después, la operación sigue ahí.
 *  2. SyncN8n.solicitar() programa un SyncWorker (WorkManager): funciona con la app cerrada y espera a
 *     que haya internet. Con la app abierta también se procesa cada 15 minutos.
 *  3. MotorSync envía las operaciones de una en una y en orden, y solo las da por hechas cuando n8n
 *     confirma {ok:true} después de guardarlas en Supabase (ver MotorSync.kt).
 *
 * La app nunca habla con Supabase: solo con n8n, con su id_clienta y su api_key.
 */
object SyncN8n {
    private const val TAG = "SyncN8n"
    private const val PROTOCOLO = 2

    private fun cfg(context: Context, id: Int): String = context.getString(id).trim()

    /** null si config.xml está bien; si no, el motivo. */
    fun errorConfig(context: Context): String? = validarConfigSync(
        cfg(context, R.string.agenda_base_url), cfg(context, R.string.agenda_pais),
        cfg(context, R.string.agenda_id_clienta), cfg(context, R.string.agenda_api_key)
    )

    /** "25/12/2026" + "17:30" → "2026-12-25T17:30" */
    private fun fechaIso(cita: Cita): String? = try {
        val entrada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { isLenient = false }
        val d = entrada.parse("${cita.fecha} ${cita.hora}")
        if (d == null) null else SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(d)
    } catch (e: Exception) {
        null
    }

    /** Datos de la cita que viajan a n8n (sin los de configuración, que se añaden al enviar). */
    fun datosCita(cita: Cita): JSONObject = JSONObject().apply {
        put("id_cita", cita.id)
        put("cliente_nombre", cita.cliente)
        put("cliente_telefono", cita.telefono)
        put("cliente_email", cita.email)
        put("fecha_hora", fechaIso(cita) ?: "")
        put("terminada", cita.terminado)
    }

    // ── Cola y reintentos ──

    /** Programa el envío de lo pendiente en segundo plano (en cuanto haya internet). */
    fun solicitar(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: si ya hay uno en marcha, este se ejecuta después (recoge lo que entre mientras);
        // si el anterior falló por configuración, lo sustituye.
        WorkManager.getInstance(context).enqueueUniqueWork("sync_n8n", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * Al abrir la app, las operaciones que n8n rechazó (bloqueadas) se vuelven a intentar, hasta 5 intentos en total.
     * Así no se quedan atascadas para siempre si el rechazo se debía a algo que ya se ha corregido
     * (por ejemplo, un borrado de una cita que ya no existe en el móvil y no se puede volver a editar).
     */
    suspend fun reintentarBloqueadas(context: Context) {
        val n = AppDatabase.getDatabase(context).syncDao().reintentarBloqueadas(MAX_INTENTOS_BLOQUEADAS)
        if (n > 0) Log.w(TAG, "Se vuelven a intentar $n operaciones rechazadas")
    }
    private const val MAX_INTENTOS_BLOQUEADAS = 5

    /** Procesa la cola ahora (lo usan el Worker y el bucle de la app). */
    suspend fun procesar(context: Context): EstadoSync {
        val db = AppDatabase.getDatabase(context)
        importarColaAntigua(context, db)
        val motor = MotorSync(ColaRoom(db), TransporteN8n(context, db), { errorConfig(context) })
        val estado = motor.procesar()
        if (estado != EstadoSync.AL_DIA) Log.w(TAG, "Sincronización: $estado · ${motor.ultimoMotivo}")
        val bloqueadas = db.syncDao().contarBloqueadas()
        if (bloqueadas > 0) Log.w(TAG, "$bloqueadas operaciones rechazadas por n8n quedan guardadas en la cola")
        return estado
    }

    /**
     * Versión anterior (parche 0002): los borrados pendientes se guardaban en SharedPreferences.
     * Se pasan a la tabla (sin pisar una operación más nueva de la misma cita) y se vacía la lista antigua.
     */
    private suspend fun importarColaAntigua(context: Context, db: AppDatabase) {
        val prefs = context.getSharedPreferences("sync_n8n", Context.MODE_PRIVATE)
        val antiguos = prefs.getStringSet("borrados_pendientes", null) ?: return
        if (antiguos.isEmpty()) { prefs.edit().remove("borrados_pendientes").apply(); return }
        db.withTransaction {
            for (texto in antiguos) {
                val json = try { JSONObject(texto) } catch (e: Exception) { continue }
                val id = json.optString("id_cita")
                if (id.isEmpty()) continue
                if (db.syncDao().deCita(id) != null) continue
                // revisión 0 = sin control de versión (como la app antigua): el servidor la aplica siempre
                db.syncDao().encolar(OperacionSync(id, UUID.randomUUID().toString(), TipoOp.BORRAR, 0,
                    json.toString(), 0, null, false, System.currentTimeMillis()))
            }
        }
        prefs.edit().remove("borrados_pendientes").apply()
    }

    /** La cola sobre Room: completar() es condicional y transaccional. */
    private class ColaRoom(private val db: AppDatabase) : ColaSync {
        override suspend fun pendientes() = db.syncDao().pendientes().map { it.aOp() }

        override suspend fun completar(op: OpSync): Boolean = db.withTransaction {
            val quitada = db.syncDao().borrarSiOp(op.idCita, op.opId) == 1
            if (quitada && op.tipo == TipoOp.GUARDAR) {
                val marcada = db.citaDao().marcarSincronizadaSiRevision(op.idCita, op.revision)
                if (marcada == 0) Log.w(TAG, "La cita ${op.idCita} cambió durante el envío: queda pendiente")
            }
            quitada
        }

        override suspend fun descartar(op: OpSync) {
            db.syncDao().borrarSiOp(op.idCita, op.opId)
        }

        override suspend fun registrarFallo(op: OpSync, motivo: String, bloquear: Boolean) {
            db.syncDao().registrarFallo(op.idCita, op.opId, motivo.take(300), bloquear)
        }
    }

    /** Envío real a n8n. */
    private class TransporteN8n(private val context: Context, private val db: AppDatabase) : TransporteSync {
        override suspend fun enviar(op: OpSync): Envio {
            val datos = when (op.tipo) {
                TipoOp.GUARDAR -> {
                    val cita = db.citaDao().obtenerPorId(op.idCita) ?: return Envio.SinDatos
                    if (cita.revision != op.revision) return Envio.Obsoleta
                    datosCita(cita)
                }
                else -> try { JSONObject(op.payload) } catch (e: Exception) { JSONObject().put("id_cita", op.idCita) }
            }
            datos.put("accion", op.tipo)
            datos.put("id_cita", op.idCita)
            datos.put("id_clienta", cfg(context, R.string.agenda_id_clienta))
            datos.put("servicio", cfg(context, R.string.agenda_servicio))
            datos.put("revision", op.revision)
            datos.put("op_id", op.opId)
            datos.put("protocolo", PROTOCOLO)
            return Envio.Enviada(post(datos.toString()))
        }

        /** Lanza IOException si no hay conexión (el motor lo trata como fallo transitorio). */
        private suspend fun post(cuerpo: String): RespuestaN8n = withContext(Dispatchers.IO) {
            val base = cfg(context, R.string.agenda_base_url).trimEnd('/')
            val pais = cfg(context, R.string.agenda_pais).lowercase(Locale.ROOT)
            val con = (URL("$base/webhook/$pais/agenda/cita-sync").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-api-key", cfg(context, R.string.agenda_api_key))
            }
            try {
                con.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
                val codigo = con.responseCode
                val flujo = if (codigo in 200..299) con.inputStream else con.errorStream
                val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val j = try { JSONObject(texto) } catch (e: Exception) { null }
                RespuestaN8n(
                    codigoHttp = codigo,
                    ok = if (j != null && j.has("ok")) j.optBoolean("ok") else null,
                    idCita = j?.optString("id_cita")?.ifEmpty { null },
                    opId = j?.optString("op_id")?.ifEmpty { null },
                    revisionServidor = if (j != null && j.has("revision_servidor") && !j.isNull("revision_servidor"))
                        j.optLong("revision_servidor") else null,
                    error = j?.optString("error")?.ifEmpty { null }
                )
            } finally {
                con.disconnect()
            }
        }
    }

    /**
     * Pregunta a n8n qué han respondido los clientes (confirmada / cancelada) y lo guarda en el móvil.
     * Si un cliente cancela, quita las alarmas de esa cita.
     * Devuelve las citas que acaban de ser canceladas por el cliente (para avisar en pantalla).
     */
    suspend fun consultarEstados(context: Context, dao: CitaDao): List<Cita> = withContext(Dispatchers.IO) {
        val base = cfg(context, R.string.agenda_base_url).trimEnd('/')
        val pais = cfg(context, R.string.agenda_pais).lowercase(Locale.ROOT)
        val idClienta = cfg(context, R.string.agenda_id_clienta)
        val apiKey = cfg(context, R.string.agenda_api_key)
        if (errorConfig(context) != null) return@withContext emptyList()

        val canceladas = mutableListOf<Cita>()
        var con: HttpURLConnection? = null
        try {
            val url = URL("$base/webhook/$pais/agenda/estados?id_clienta=${URLEncoder.encode(idClienta, "UTF-8")}")
            con = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("x-api-key", apiKey)
            }
            if (con.responseCode !in 200..299) {
                Log.w(TAG, "Estados: n8n respondió ${con.responseCode}")
                return@withContext emptyList()
            }
            val texto = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val lista = JSONObject(texto).optJSONArray("estados") ?: return@withContext emptyList()
            for (i in 0 until lista.length()) {
                val o = lista.getJSONObject(i)
                val id = o.optString("id_cita")
                val estado = o.optString("estado")
                if (estado !in setOf("pendiente", "confirmada", "cancelada")) continue
                val local = dao.obtenerPorId(id) ?: continue
                if (local.estadoCliente == estado) continue
                dao.actualizarEstadoCliente(id, estado)
                if (estado == "cancelada" && !local.terminado) {
                    cancelarAlarma(context, local)
                    canceladas.add(local)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Estados: sin conexión con n8n: ${e.message}")
        } finally {
            con?.disconnect()
        }
        canceladas
    }

    // ── Avisos al cliente final desde el WhatsApp de la profesional (gratis) ──

    private fun digitosLocales(context: Context): Int =
        if (cfg(context, R.string.agenda_pais).uppercase(Locale.ROOT) == "CO") 10 else 9

    private fun prefijo(context: Context): String =
        if (cfg(context, R.string.agenda_pais).uppercase(Locale.ROOT) == "CO") "57" else "34"

    /** Número con prefijo de país y sin "+" ni espacios, como lo quiere wa.me */
    fun telefonoInternacional(context: Context, telefono: String): String {
        val d = telefono.filter { it.isDigit() }.removePrefix("00")
        val n = digitosLocales(context)
        return if (d.length > n) d else prefijo(context) + d.takeLast(n)
    }

    /** Enlace para que el cliente final active los recordatorios por Telegram */
    fun enlaceTelegram(context: Context, cita: Cita): String {
        val bot = cfg(context, R.string.agenda_bot_recordatorios).removePrefix("@")
        val tel = cita.telefono.filter { it.isDigit() }.takeLast(digitosLocales(context))
        return "https://t.me/$bot?start=c_${cfg(context, R.string.agenda_id_clienta)}_$tel"
    }

    /** Abre WhatsApp con el mensaje ya escrito para ese cliente (la profesional solo pulsa enviar) */
    fun abrirWhatsApp(context: Context, telefono: String, texto: String) {
        if (telefono.none { it.isDigit() }) {
            Toast.makeText(context, "Esta cita no tiene teléfono", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("https://wa.me/${telefonoInternacional(context, telefono)}?text=${Uri.encode(texto)}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    fun textoRecordatorio(context: Context, cita: Cita): String {
        val servicio = cfg(context, R.string.agenda_servicio).lowercase(Locale.getDefault())
        val lugar = if (cita.direccion.isNotBlank()) " en ${cita.direccion}" else ""
        return "¡Hola, ${cita.cliente}! Te recuerdo tu cita de $servicio el ${cita.fecha} a las ${cita.hora}$lugar. " +
            "¿Me confirmas que te viene bien? ¡Gracias!"
    }

    fun textoEnlaceTelegram(context: Context, cita: Cita): String =
        "¡Hola, ${cita.cliente}! Para recibir los recordatorios de tus citas (y confirmarlas con un toque), " +
            "pulsa este enlace y dale a \"Iniciar\": ${enlaceTelegram(context, cita)}"
}

// El precio por hora está ahora en Cobros.kt (se puede cambiar desde Ajustes en la app).

/** Envía la cola en segundo plano: funciona con la app cerrada y espera a que haya internet. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (SyncN8n.procesar(applicationContext)) {
        EstadoSync.AL_DIA -> Result.success()
        // Queda algo pendiente (sin red, n8n caído, o entraron cambios mientras se enviaba): reintentar
        EstadoSync.PENDIENTE -> Result.retry()
        // config.xml incompleto o n8n rechaza la clave: reintentar no sirve. Las operaciones siguen
        // guardadas y se enviarán cuando se corrija (al abrir la app o al guardar otra cita).
        EstadoSync.ERROR_CONFIG -> Result.failure()
    }
}
