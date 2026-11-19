package com.example.agendatatuajes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Envía las citas a n8n (webhook /webhook/{pais}/agenda/cita-sync) para que se guarden
 * en la hoja Citas y salgan los recordatorios a los clientes.
 * La agenda sigue funcionando igual sin internet ni n8n (apagón, PC apagado...):
 *  - Citas creadas/editadas/terminadas: quedan marcadas como no sincronizadas en el móvil.
 *  - Citas BORRADAS: como ya no existen en el móvil, su aviso se guarda en una cola aparte.
 * En ambos casos se programa un reintento en segundo plano (WorkManager, con la app cerrada
 * y en cuanto haya internet) y también se reintenta cada 15 min con la app abierta.
 */
object SyncN8n {
    private const val TAG = "SyncN8n"

    private fun cfg(context: Context, id: Int): String = context.getString(id).trim()

    /** Un solo envío a la vez y en orden: así n8n nunca recibe una versión vieja después de la nueva. */
    private val envio = Mutex()

    /** Marca la cita como sincronizada solo si no ha cambiado desde que se envió. */
    suspend fun marcarSiNoCambio(dao: CitaDao, enviada: Cita) {
        dao.marcarSincronizadaSiNoCambio(enviada.id, enviada.cliente, enviada.telefono, enviada.email,
            enviada.fecha, enviada.hora, enviada.terminado)
    }

    private const val PREFS = "sync_n8n"
    private const val KEY_BORRADOS = "borrados_pendientes"

    /** Avisos de borrado que n8n aún no ha recibido (JSON ya montado, uno por cita). */
    private fun borradosPendientes(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BORRADOS, emptySet())!!.toMutableSet()

    private fun guardarBorrados(context: Context, set: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_BORRADOS, set).apply()
    }

    /** Programa un reintento en segundo plano en cuanto haya conexión (si ya hay uno programado, no duplica). */
    fun programarReintento(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync_n8n", ExistingWorkPolicy.KEEP, req)
    }

    /** Config lista para enviar a n8n (null si config.xml está sin rellenar). */
    private fun configOk(context: Context): Boolean {
        val base = cfg(context, R.string.agenda_base_url)
        val idClienta = cfg(context, R.string.agenda_id_clienta)
        val apiKey = cfg(context, R.string.agenda_api_key)
        return base.isNotEmpty() && idClienta.isNotEmpty() && !idClienta.startsWith("PON_AQUI") && !apiKey.startsWith("PON_AQUI")
    }

    /** "25/12/2026" + "17:30" → "2026-12-25T17:30" */
    private fun fechaIso(cita: Cita): String? = try {
        val entrada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { isLenient = false }
        val d = entrada.parse("${cita.fecha} ${cita.hora}")
        if (d == null) null else SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(d)
    } catch (e: Exception) {
        null
    }

    /**
     * accion = "guardar" (crear, editar o terminar) o "borrar".
     * Devuelve true si n8n ha confirmado que la ha guardado.
     */
    suspend fun enviar(context: Context, cita: Cita, accion: String): Boolean {
        val json = montarJson(context, cita, accion) ?: return false
        return post(context, json.toString())
    }

    /**
     * Borrar una cita: avisa a n8n para que la marque como cancelada y no mande recordatorio.
     * Si n8n no responde, el aviso queda en cola y se reenvía solo más tarde.
     */
    suspend fun borrar(context: Context, cita: Cita) {
        val json = montarJson(context, cita, "borrar") ?: return
        if (!post(context, json.toString())) {
            guardarBorrados(context, borradosPendientes(context).apply { add(json.toString()) })
            programarReintento(context)
        }
    }

    private fun montarJson(context: Context, cita: Cita, accion: String): JSONObject? {
        if (!configOk(context)) {
            Log.w(TAG, "Falta configurar config.xml: no se envía nada a n8n")
            return null
        }
        val idClienta = cfg(context, R.string.agenda_id_clienta)
        val fecha = fechaIso(cita) ?: return null
        return JSONObject().apply {
            put("accion", accion)
            put("id_cita", cita.id)
            put("id_clienta", idClienta)
            put("cliente_nombre", cita.cliente)
            put("cliente_telefono", cita.telefono)
            put("cliente_email", cita.email)
            put("servicio", cfg(context, R.string.agenda_servicio))
            put("fecha_hora", fecha)
            put("terminada", cita.terminado)
        }
    }

    /** POST al webhook cita-sync. true si n8n contesta 2xx. */
    private suspend fun post(context: Context, cuerpo: String): Boolean = envio.withLock { postSinCola(context, cuerpo) }

    private suspend fun postSinCola(context: Context, cuerpo: String): Boolean = withContext(Dispatchers.IO) {
        val base = cfg(context, R.string.agenda_base_url).trimEnd('/')
        val pais = cfg(context, R.string.agenda_pais).lowercase(Locale.ROOT)
        val apiKey = cfg(context, R.string.agenda_api_key)
        var con: HttpURLConnection? = null
        try {
            con = (URL("$base/webhook/$pais/agenda/cita-sync").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-api-key", apiKey)
            }
            con.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            if (codigo !in 200..299) Log.w(TAG, "n8n respondió $codigo")
            codigo in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Sin conexión con n8n: ${e.message}")
            false
        } finally {
            con?.disconnect()
        }
    }

    /**
     * Reintenta lo que no se pudo enviar (sin internet o con n8n caído): primero los borrados
     * y luego las citas no sincronizadas. Devuelve true si ya no queda nada pendiente.
     */
    suspend fun reintentarPendientes(context: Context, dao: CitaDao): Boolean {
        if (!configOk(context)) return true
        val borrados = borradosPendientes(context)
        val enviados = borrados.filter { post(context, it) }.toSet()
        if (enviados.isNotEmpty()) guardarBorrados(context, borrados - enviados)
        var todo = enviados.size == borrados.size
        for (cita in dao.obtenerNoSincronizadas()) {
            if (enviar(context, cita, "guardar")) marcarSiNoCambio(dao, cita) else todo = false
        }
        return todo
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
        if (base.isEmpty() || idClienta.startsWith("PON_AQUI") || apiKey.startsWith("PON_AQUI")) return@withContext emptyList()

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

/** Reintento en segundo plano: funciona con la app cerrada y espera a que haya internet. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = AppDatabase.getDatabase(applicationContext).citaDao()
        return if (SyncN8n.reintentarPendientes(applicationContext, dao)) Result.success() else Result.retry()
    }
}
