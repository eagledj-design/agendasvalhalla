# Sincronización Android → n8n → Supabase: cola persistente y revisiones

Este cambio **sustituye a los parches `0002-sync-offline-borrados` y `0003-sync-sin-carreras`**.
El 0002 ya está en `main` (commit 131d7a7) y este parche va encima. El 0003 **no se debe aplicar**.

La arquitectura no cambia: Android solo habla con n8n (webhook + `id_clienta` + `api_key`) y n8n es el único que accede a Supabase. No hay credenciales de Supabase en la app.

## Archivos

| Archivo | Qué es |
|---|---|
| `MotorSync.kt` (nuevo) | Motor de sincronización en Kotlin puro (sin Android), para poder probarlo en la JVM. Incluye la cola, la clasificación de respuestas, la validación de config y el Mutex. |
| `OperacionSync.kt` (nuevo) | Entidad Room `operaciones_sync` y `SyncDao`. |
| `CitaRepo.kt` (nuevo) | Único punto de guardado y borrado de citas: cita, revisión y cola en una transacción. |
| `SyncN8n.kt` | Adaptador: cola sobre Room, transporte HTTP, importación de la cola antigua, `SyncWorker` y `solicitar()`. |
| `Cita.kt` | Campo `revision`. |
| `CitaDao.kt` | `revisionDe` y `marcarSincronizadaSiRevision`. Se eliminan `marcarSincronizada` (incondicional) y `obtenerNoSincronizadas`. |
| `AppDataBase.kt` | Versión 11 y `MIGRATION_10_11`. |
| `MainActivity.kt` | Todas las escrituras pasan por `CitaRepo`. |
| `app/src/test/.../MotorSyncTest.kt` | 19 pruebas JUnit del motor. |
| `app/src/test/sql/test_room_sqlite.py` | 28 pruebas de migraciones y consultas Room en SQLite. |

Cambios de servidor (se entregan aparte, en `agendas-n8n`):
- `supabase/migracion_02_revision_citas.sql`
- `supabase/pruebas/test_sync_cita.py`
- `ES_espana/ES_02_seccionD_sync_v2_PEGAR.json` y `CO_colombia/CO_02_seccionD_sync_v2_PEGAR.json`
- `_build/pruebas/test_nodos_sync.js`

## Corrección por punto de la auditoría

1. **Cola en Room en lugar de SharedPreferences.** Nueva tabla `operaciones_sync`. Las operaciones que quedaron en SharedPreferences con el 0002 se importan a la tabla una sola vez (`importarColaAntigua`) y después se vacía la lista antigua. Se importan con revisión 0, que el servidor aplica sin control de versión, igual que antes.

2. **Borrado y cola en una única transacción.** `CitaRepo.borrar` hace `db.withTransaction { encolar(borrar) ; borrarCita }`. Una prueba en SQLite demuestra que, si falla a mitad, no queda nada a medias (rollback).

3. **Cola persistente con identificador único.** Cada operación lleva un `opId` (UUID), su `tipo`, su `revision`, `intentos`, `ultimoError`, `bloqueada` y `creadaEn`. Para un borrado, además, guarda una copia de los datos en JSON, porque la cita ya no existe en el móvil. La clave primaria es `idCita`, así que hay **una operación por cita**.

4. **`configOk()` reforzado.** `validarConfigSync()` exige:
   - URL `https://dominio`, sin barra final ni `/webhook`;
   - país ES o CO;
   - `id_clienta` real;
   - `api_key` no vacía, sin `PON_AQUI`/`TU_`, de al menos 16 caracteres y con caracteres válidos.

   Lo usan tanto el envío como `consultarEstados`.

5. **WorkManager no da éxito con trabajo pendiente.** `SyncWorker` devuelve:
   - `success` solo si la cola queda vacía (`AL_DIA`);
   - `retry` en fallos transitorios o si entraron cambios mientras enviaba (`PENDIENTE`);
   - `failure` en errores de configuración (`ERROR_CONFIG`: config.xml o 401/403), para no reintentar en bucle.

   Las operaciones no se borran nunca por un error de configuración. Clasificación de respuestas:
   - **Transitorios** (se reintenta): sin red, 5xx, 408, 429, 404 (webhook inactivo) y 2xx sin confirmación.
   - **Permanentes de la operación** (400, 409, 422): la operación queda **bloqueada pero guardada**, y se desbloquea sola si la cita se vuelve a editar.

6. **Revisión por cita y marcado atómico.** `Cita.revision` sube en 1 en cada guardado o borrado, dentro de la transacción de `CitaRepo`. Nunca baja, porque se toma el máximo entre la revisión de la cita y la de la operación pendiente. `marcarSincronizadaSiRevision` es un `UPDATE … WHERE id = ? AND revision = ?`. Además, `completar()` borra la operación con `WHERE idCita = ? AND opId = ?` dentro de la misma transacción.

7. **Revisión en la petición y validación atómica en Supabase.** La app envía `revision`, `op_id` y `protocolo: 2`. En Supabase, `public.sync_cita()` hace un solo `INSERT … ON CONFLICT (id_cita) DO UPDATE … WHERE c.id_clienta = excluded.id_clienta AND (excluded.revision = 0 OR c.revision < excluded.revision)`. La comparación y la escritura ocurren con la fila bloqueada, así que dos peticiones simultáneas no pueden pisarse; está probado con dos sesiones reales a la vez. La revisión 0 mantiene la compatibilidad con la app antigua.

8. **Resultado del marcado comprobado.** Si la operación ya fue sustituida por otra más nueva, `completar()` devuelve `false` y no marca nada. El motor hace otra pasada y envía la versión nueva. Si al final sigue habiendo pendientes, devuelve `PENDIENTE` y WorkManager reintenta.

9. **Confirmación explícita de n8n.** Una operación solo se da por hecha si n8n responde HTTP 2xx con `ok: true`, el mismo `id_cita`, el mismo `op_id` (si viene) y `revision_servidor >= revisión enviada`. n8n solo responde así **después** de que `sync_cita` haya devuelto el resultado de la base de datos.

10. **Idempotencia y borrado de citas terminadas.** Repetir una operación, o recibirla tarde, no cambia nada en el servidor: responde `ok: true, aplicada: false`, con lo que la app la da por hecha y no entra en bucle. Borrar una cita terminada **ahora sí se envía**. El servidor la deja como `terminada`, porque es historial y cuenta en los resúmenes, pero registra la revisión para que ningún guardado antiguo pendiente la pise. Antes no se avisaba.

11. **Conflictos entre guardar y borrar la misma cita.** Al haber una operación por cita, un borrado **sustituye** al guardado pendiente y solo viaja el borrado. En el servidor, un guardado que llegue tarde tiene una revisión menor que el borrado y no resucita la cita. Está probado en Kotlin y en PostgreSQL.

12. **Migraciones sin pérdida de datos.**
   - **Room 10→11:** añade la columna `revision` (por defecto 0) y la tabla `operaciones_sync`, y encola como `guardar` (revisión 1) las citas que estaban sin sincronizar.
   - **Supabase:** añade `revision` y `ultima_op` con valor por defecto, más la función. Es idempotente (`if not exists` / `create or replace`).
   - **Probado:** la cadena completa 6→7→…→11 con datos, y que el esquema final coincide con las entidades.

## Pruebas ejecutadas y resultados reales

| Suite | Qué ejecuta | Resultado |
|---|---|---|
| `MotorSyncTest.kt` | Motor real (`MotorSync.kt`) compilado con Kotlin 2.0.21 + coroutines 1.6.4. Cola y servidor simulados con la misma semántica que Room y que `sync_cita`. | **19/19** |
| Mutaciones del motor | 5 errores introducidos a propósito (aceptar un 200 sin `ok`, quitar el Mutex, no comprobar la revisión, dar por bueno un 400, tratar el 401 como transitorio). | **5/5 detectadas** |
| `test_room_sqlite.py` | Migraciones y `@Query` **leídas del código Kotlin** y ejecutadas en SQLite 3.53. Base inicial en v6 con datos. | **28/28** |
| Mutaciones SQLite | 3 errores (no encolar en la migración, tipo de columna erróneo, marcar sin mirar la revisión). | **3/3 detectadas** |
| `test_sync_cita.py` | Migración y función en PostgreSQL 16 local y desechable, incluida la concurrencia con dos sesiones simultáneas. | **22/22** |
| `test_nodos_sync.js` | JavaScript de los nodos Code de la sección D (ES y CO), extraído del JSON que se importa en n8n. | **21/21 + 21/21** |
| Comprobación de tipos | `SyncN8n`, `CitaRepo`, `OperacionSync`, `AppDataBase`, `CitaDao`, `Cita` y `MotorSync` compilados contra firmas mínimas de Room, WorkManager, Context y org.json. | **0 errores** |

Escenarios cubiertos:
- **Desconexión:** sin red, error 500 y webhook inactivo.
- **Borrados:** borrado sin conexión, guardado pendiente seguido de borrado, y guardado tardío después de un borrado.
- **Reintentos:** respuesta perdida, con el reintento idempotente.
- **Cambios simultáneos:** edición durante el envío, y 30 ediciones concurrentes con sincronizaciones en paralelo hasta quedar consistentes.
- **Respuestas retrasadas:** revisión vieja que llega después de la nueva, tanto en la app como en la base de datos.
- **Servidor:** n8n sin confirmación, respuesta de otra cita u otra operación, 400, 401 y config incompleta.
- **Exclusión mutua:** nunca hay dos envíos a la vez.

## Lo que NO se ha podido hacer aquí (pendiente de verificar)

- **Compilar la APK.** Este entorno no tiene el SDK de Android ni acceso a Maven. Hay que ejecutar `./gradlew assembleDebug test` en Android Studio; `MotorSyncTest` corre con el JUnit real del proyecto. Las pruebas de aquí usaron un sustituto mínimo de JUnit y la comprobación de tipos usó firmas sustitutas de las APIs de Android.
- **Validación de esquema de Room al abrir la base.** Se ha aproximado comparando `PRAGMA table_info` con las entidades. La prueba definitiva es instalar encima de una versión 10 con datos.
- **n8n.** No se ha ejecutado ningún flujo de producción ni se ha enviado ningún recordatorio. El JS de los nodos se probó en Node con un sustituto de `DateTime` (Luxon), con las mismas llamadas que ya usaba el flujo anterior. Falta probar el flujo en n8n con una clienta de prueba.
- **Supabase de producción.** La migración **no se ha aplicado**: solo se ha leído el esquema real para confirmar que coincide con el usado en las pruebas.

## Despliegue (en este orden)

1. **Supabase.** Ejecutar `migracion_02_revision_citas.sql` en AgendasEspaña y en AgendasColombia. No afecta al flujo actual: las columnas nuevas tienen valor por defecto.
2. **n8n.** En el flujo `2 · Motor de la app` de cada país:
   - borrar los nodos antiguos de la sección D (`App · sincronizar cita`, `Validar sync`, `Buscar clienta (sync)`, `Buscar cita (sync)`, `Preparar cita (sync)` y `Guardar o actualizar cita`);
   - pegar `XX_02_seccionD_sync_v2_PEGAR.json` (copiar el contenido y Ctrl+V en el lienzo);
   - asignar la credencial Postgres del país a `Buscar clienta (sync)` y `Guardar cita (sync_cita)`;
   - en `Comprobar clienta (sync)`, sustituir `TU_API_KEY_AGENDA_XX` como se hizo en el resto del flujo;
   - guardar.

   El path del webhook no cambia. Las apps antiguas siguen funcionando, porque sin revisión se tratan como revisión 0.
3. **Probar con una clienta de prueba** (no real): crear, editar, borrar y terminar citas, y comprobar en `citas` las columnas `revision`, `estado` y `ultima_op`.
4. **App.** Compilar e instalar **encima** de la versión actual (no desinstalar) para que se ejecute la migración 10→11.

Si la app nueva se instala antes de actualizar n8n, no se pierde nada: el flujo antiguo no responde `{ok:true}`, así que la app se queda reintentando hasta que n8n se actualice.

## Observaciones fuera del alcance (sin cambiar)

- ~~`fallbackToDestructiveMigration()`~~ y ~~cancelaciones del cliente que vuelven a `pendiente`~~: **corregidos**, ver la última sección.
- **Cita recreada con el mismo id:** si se volviera a crear una cita con el mismo id después de que su borrado ya se hubiera enviado, empezaría en revisión 1 y el servidor la ignoraría. No ocurre con el uso normal, porque los ids son UUID nuevos.

## Corrección posterior (prueba real en el móvil, 19/11)

En la prueba real (crear, editar sin conexión y borrar) n8n rechazó el borrado con
`400 campo_obligatorio: fecha_hora`. La causa era **un fallo antiguo del formulario**, no de la sincronización:

- **El fallo:** al **editar** una cita, la fecha se cargaba con barras (`22/11/2026`). Si no se tocaba ese campo, al guardar se troceaba como si fueran 8 dígitos y quedaba `22//1/1/2026`; con la hora pasaba igual (`18::00`). Esa fecha rota también impedía programar las alarmas.
- **La consecuencia en la cola:** el borrado de esa cita se quedó **bloqueado para siempre**. Como la cita ya no existe en el móvil, no se podía editar para desbloquearlo.

Correcciones:
1. **Formulario.** `FechasCita.kt` (`soloDigitos` y `fechaHoraDeFormulario`): el formulario guarda la fecha y la hora solo en dígitos y comprueba que existen (calendario estricto) antes de guardar; si no, avisa. 6 pruebas nuevas.
2. **Servidor: borrar sin fecha.**
   - **Supabase:** la migración `migracion_03_borrar_sin_fecha.sql` hace que `sync_cita` cancele la cita existente solo con el id (misma clienta, revisión mayor). Si el servidor nunca tuvo la cita, responde `sin_cita` sin crear nada. Guardar sin fecha sigue siendo un error.
   - **n8n:** `Validar sync` acepta borrados sin fecha o con la fecha rota, y `Resultado sync` confirma `sin_cita` con la revisión enviada.
3. **Operaciones bloqueadas.** Se reintentan **al abrir la app** (`SyncDao.reintentarBloqueadas`), hasta 5 intentos en total. Así se recuperan solas cuando se corrige la causa, sin reintentar en bucle.

Resultados tras la corrección:

| Suite | Resultado |
|---|---|
| Motor + fechas (Kotlin) | **25/25** |
| Room/SQLite | **31/31** |
| PostgreSQL (migraciones 02 + 03, con concurrencia) | **30/30** |
| Nodos n8n | **26/26** en ES y en CO |
| Comprobación de tipos | 0 errores |


## Segunda revisión de la auditoría (19/11)

1. **Despliegue en orden.** Hecho y verificado en producción: migraciones 02 y 03 en los dos proyectos de Supabase, después los nodos de n8n y por último la app. La prueba real en el móvil quedó así: cita creada, editada y borrada sin conexión, y al final `cancelada` con revisión 3. La migración 04 de este cambio se aplica igual, en los dos proyectos, antes de usar la app con clientas reales.

2. **`fallbackToDestructiveMigration()` eliminado.** Si faltara una migración, Room dará un error al abrir en lugar de borrar los datos en silencio. Dos pruebas nuevas en SQLite comprueban que no vuelve a aparecer y que las migraciones forman una cadena sin huecos desde la 6 hasta la versión actual. Ojo: antes de instalar AgendaBase **encima** de una agenda antigua con el mismo paquete y la misma base (`agenda_tatuajes_db`), hay que comprobar su versión de base de datos. Si es anterior a la 6, primero hay que añadir sus migraciones.

3. **Cancelación del cliente respetada.** Con `migracion_04_respetar_cancelacion.sql`, si el cliente canceló (flujo C) y la profesional edita la cita **sin cambiar la fecha**, la cita sigue `cancelada` y no se reprograma el recordatorio. Si cambia la fecha, es una cita nueva y vuelve a `pendiente`. Si la marca terminada, queda `terminada`. La confirmación del cliente se respeta como antes.
   - **Prueba específica:** 6 casos nuevos en PostgreSQL. Sin la migración 04, la prueba reproduce el fallo (`pendiente`); con ella pasa.

| Suite | Resultado |
|---|---|
| PostgreSQL (migraciones 02 + 03 + 04, con concurrencia) | **37/37** |
| Room/SQLite (incluye las pruebas anti-borrado y de cadena de migraciones) | **33/33** |
| Motor + fechas (Kotlin) | **25/25** (sin cambios) |
