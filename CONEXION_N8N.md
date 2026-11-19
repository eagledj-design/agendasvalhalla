# Conexión de la agenda con n8n

La agenda sigue funcionando igual en el móvil (Room, sin internet). Además, ahora **envía cada cita a n8n**
para que se guarde en la hoja `Citas` de su país y salgan los recordatorios a los clientes (plan Pro).

## Qué ha cambiado

| Archivo | Cambio |
| --- | --- |
| `res/values/config.xml` | **Nuevo.** Datos de esta clienta: país, id_clienta, api_key, URL de n8n, nombre del servicio y precio por hora. |
| `SyncN8n.kt` / `MotorSync.kt` / `CitaRepo.kt` / `OperacionSync.kt` | Envían las citas a `POST /webhook/{es|co}/agenda/cita-sync` desde una cola persistente con revisiones (ver abajo). |
| `Cita.kt` | Dos campos nuevos: `email` (del cliente) y `sincronizado`. |
| `AppDataBase.kt` | Versión 7 con **migración 6→7**: añade las columnas **sin borrar las citas que ya existen**. |
| `CitaDao.kt` | Consultas para las citas no sincronizadas. |
| `AndroidManifest.xml` | Permiso `INTERNET` (antes no lo tenía). |
| `MainActivity.kt` | Campo "Email del cliente" en el formulario. Envía la cita al **guardar, editar, terminar y borrar**. Reintenta al abrir la app. El precio por hora sale de `config.xml` (antes 12 € fijo). Muestra ✅/❌ lo que ha respondido el cliente y dos botones nuevos en cada cita pendiente. |

## Lo que ve la profesional en cada cita pendiente

- **✅ Confirmada por el cliente** / **❌ Cancelada por el cliente**: la app pregunta a n8n al abrirse y cada 15 minutos mientras está abierta. Si el cliente cancela, sale un aviso y **se quitan las alarmas** de esa cita.
- Botón verde (enviar): **Recordar por WhatsApp**. Abre WhatsApp con el recordatorio ya escrito para ese cliente; ella solo pulsa enviar. Gratis.
- Botón azul (campana): **Enviar enlace de recordatorios**. Abre WhatsApp con el enlace del bot de Telegram de recordatorios. Cuando el cliente pulsa *Iniciar*, queda vinculado y **todas sus citas futuras con esa profesional** se le recuerdan por Telegram automáticamente (plan Pro), con botones de Confirmar / No puedo ir.

## Canales de recordatorio automático (plan Pro)

1. **Telegram**, si el cliente se ha vinculado con el bot (gratis).
2. **Correo**, si la cita tiene email.
3. **WhatsApp**, cuando se active (de pago).

## Cuándo se envía cada cosa (protocolo 2: cola + revisiones)

Todo cambio pasa por `CitaRepo`: en **una sola transacción** de Room se guarda o borra la cita, se sube su
`revision` y se deja la operación en la tabla `operaciones_sync`. Después la envía `SyncN8n` en segundo plano
(WorkManager, también con la app cerrada y en cuanto haya internet).

| En la app | En Supabase (`citas`) |
| --- | --- |
| Guardar una cita nueva | Fila nueva con estado `pendiente` y su revisión |
| Editar una cita | Se actualiza la misma fila **solo si la revisión es mayor**. Si cambia la fecha u hora, se reprograma el recordatorio |
| Terminar (✔️) | Estado `terminada`: ya no se manda recordatorio |
| Borrar una cita sin terminar | Estado `cancelada`: no se manda recordatorio (la fila se conserva) |
| Borrar una cita terminada | Sigue `terminada` (es historial), pero queda registrado como último cambio |
| Sin internet / n8n caído | La operación se queda en la cola del móvil y se envía sola cuando vuelve |
| Editar varias veces sin conexión | Solo viaja la última versión (una operación por cita) |
| Respuesta que llega tarde o repetida | Se ignora: Supabase nunca aplica una revisión menor o igual a la que ya tiene |

La app solo da una operación por hecha si n8n responde **`{ "ok": true, "id_cita": …, "revision_servidor": … }`**
después de guardarla en Supabase. Si n8n responde 401 (clave incorrecta) o el `config.xml` está incompleto, deja de
reintentar en bucle pero **no borra nada**: lo pendiente se envía cuando se corrige. Si n8n rechaza una operación
por datos inválidos (400), esa operación queda guardada y marcada; se vuelve a enviar si la cita se edita.

> ⚠️ Orden de despliegue: primero la migración de Supabase (`migracion_02_revision_citas.sql`), después la
> sección D nueva del flujo 2 en n8n, y por último esta versión de la app. Con el flujo D antiguo, la app nueva
> no recibe `{ok:true}` y se queda reintentando (sin perder nada) hasta que se actualice n8n.

## Para cada agenda nueva

1. Aprueba el alta en Telegram. El bot ADMIN te manda **`agenda_id_clienta`** y **`agenda_api_key`**.
2. Copia el proyecto, cambia `fondo.png`, `marco.png`, colores y nombre como siempre.
3. Rellena **`res/values/config.xml`** con esos datos, el país (`ES` o `CO`), el servicio, el precio por hora y el **bot de recordatorios** de su país.
4. Compila e instala.

Para las agendas que **ya existían** (la de tu mujer, la de tu cuñada y esta de limpieza):
- Dales de alta en la hoja `Clientas` del país (a mano o con el formulario) y pon una `api_key` inventada en su columna.
- Pon esos datos en su `config.xml`, compila e **instala encima** (no desinstales: así conservan sus citas).
- Al abrirla, todas las citas antiguas se envían solas a n8n.

## Probarlo

1. En n8n, activa el flujo `2 · Motor de la app` del país.
2. Crea una cita en la app → debe aparecer en la hoja `Citas` en unos segundos.
3. Edítala, cámbiale la hora → se actualiza la misma fila.
4. Bórrala → pasa a `cancelada`.
5. Si no aparece: en Android Studio, **Logcat** y filtra por `SyncN8n`; ahí verás si falta configurar `config.xml`, si n8n responde un error o si no hay conexión. En n8n, mira **Executions** del flujo 2.

## Ojo

- **Nunca desinstales** la app para actualizarla: desinstalar borra la base de datos del móvil.
- Si algún día vuelves a cambiar la clase `Cita`, sube la versión de la base de datos **y añade su `Migration`** en `AppDataBase.kt`. Sin migración, `fallbackToDestructiveMigration()` borra todas las citas.
- La `api_key` va dentro de la app. No es secreta del todo (se puede extraer del APK), pero como es una por clienta, si una se filtra solo cambias esa en la hoja y en su `config.xml`.
