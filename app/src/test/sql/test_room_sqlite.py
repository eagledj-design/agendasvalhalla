"""
Prueba de las migraciones y consultas de Room en SQLite real (el motor que usa Android).
Lee el SQL DIRECTAMENTE de los .kt (migraciones de AppDataBase.kt y @Query de los DAO), no de copias.
Uso: python3 app/src/test/sql/test_room_sqlite.py   (desde la raíz del proyecto)

Qué NO cubre: la validación de esquema que hace Room al abrir la base. Para aproximarla se compara
PRAGMA table_info con el esquema que Room generaría a partir de las entidades.
"""
import re, sqlite3, os, sys, uuid

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "main", "java", "com", "example", "agendatatuajes")
leer = lambda f: open(os.path.join(RAIZ, f), encoding="utf-8").read()
resultados = []


def caso(nombre, cond, detalle=""):
    resultados.append(bool(cond))
    print(("PASA " if cond else "FALLA") + " · " + nombre + (f"  [{detalle}]" if detalle and not cond else ""))


# ---------- esquema esperado a partir de las entidades ----------
TIPOS = {"String": "TEXT", "Int": "INTEGER", "Long": "INTEGER", "Boolean": "INTEGER", "Double": "REAL"}


def campos_entidad(src, clase, hasta=None):
    cuerpo = src[src.index(f"data class {clase}("):]
    cuerpo = cuerpo[: cuerpo.index("\n)") + 2]
    if hasta:
        cuerpo = cuerpo[: cuerpo.index(hasta)]
    out = []
    for m in re.finditer(r"(@PrimaryKey\s+)?val\s+(\w+)\s*:\s*(\w+)(\?)?", cuerpo):
        out.append((m.group(2), TIPOS[m.group(3)], m.group(4) is None, m.group(1) is not None))
    return out


def ddl(tabla, campos):
    cols = ", ".join(f"`{n}` {t}{' NOT NULL' if nn else ''}" for n, t, nn, _ in campos)
    pk = [n for n, _, _, p in campos if p]
    return f"CREATE TABLE `{tabla}` ({cols}, PRIMARY KEY(`{pk[0]}`))"


def info(con, tabla):
    return [(r[1], r[2].upper(), bool(r[3]), bool(r[5])) for r in con.execute(f"PRAGMA table_info(`{tabla}`)")]


# ---------- SQL de las migraciones, leído de AppDataBase.kt ----------
def migraciones(src):
    res = {}
    for m in re.finditer(r"val MIGRATION_(\d+)_(\d+) = object : Migration\(\d+, \d+\) \{(.*?)\n        \}\n", src, re.S):
        sentencias = []
        for e in re.finditer(r"db\.execSQL\((.*?)\)\n", m.group(3), re.S):
            partes = re.findall(r'"((?:[^"\\]|\\.)*)"', e.group(1))
            sentencias.append("".join(partes).replace('\\"', '"'))
        res[(int(m.group(1)), int(m.group(2)))] = sentencias
    return res


def consultas(src):
    q = {}
    for m in re.finditer(r'@Query\("((?:[^"\\]|\\.)*)"\)\s*\n\s*(?:suspend\s+)?fun\s+(\w+)', src):
        q[m.group(2)] = m.group(1)
    return q


def ejecutar(con, sql, **p):
    return con.execute(re.sub(r":(\w+)", r":\1", sql), p)


cita_src, db_src = leer("Cita.kt"), leer("AppDataBase.kt")
MIG = migraciones(db_src)
caso("Se leen las migraciones 6→7 … 10→11 del código", all(k in MIG for k in [(6, 7), (7, 8), (8, 9), (9, 10), (10, 11)]), str(sorted(MIG)))
caso("Room la registra en addMigrations()", "MIGRATION_10_11)" in db_src and "version = 11" in db_src)
caso("No hay fallbackToDestructiveMigration (Room no puede borrar datos en silencio)",
     "fallbackToDestructiveMigration" not in re.sub(r"//.*", "", db_src))
version = int(re.search(r"version = (\d+)", db_src).group(1))
registradas = re.findall(r"MIGRATION_(\d+)_(\d+)", re.search(r"addMigrations\((.*?)\)", db_src).group(1))
cadena = sorted((int(a), int(b)) for a, b in registradas)
caso("Las migraciones registradas forman una cadena sin huecos de la 6 a la versión actual",
     cadena == [(v, v + 1) for v in range(6, version)], str(cadena))

# ---------- base en versión 6 con datos reales de ejemplo ----------
con = sqlite3.connect(":memory:")
v6 = campos_entidad(cita_src, "Cita", hasta="Nuevo en la versión 7")
con.execute(ddl("citas_tatuajes", v6))
filas = [
    ("a1", "Laura", "600111222", "02/12/2026", "10:00", "C/ Mayor 1", "2", "nota", 0, "0", "", None, 2),
    ("a2", "Marta", "600333444", "20/11/2026", "17:30", "", "3", "", 1, "60", "0", "file:///foto.jpg", 2),
    ("a3", "Sofía", "600555666", "25/11/2026", "09:00", "", "1.5", "", 1, "10", "8", None, 1),
]
con.executemany("INSERT INTO citas_tatuajes VALUES (" + ",".join("?" * 13) + ")", filas)
for v in range(6, 11):
    for s in MIG[(v, v + 1)]:
        con.execute(s)
    if v == 6:   # en la vida real, citas ya enviadas con la v7-v10
        con.execute("UPDATE citas_tatuajes SET sincronizado = 1 WHERE id = 'a2'")
con.commit()

caso("Las 3 citas siguen ahí tras migrar 6→11", con.execute("select count(*) from citas_tatuajes").fetchone()[0] == 3)
datos = con.execute("select id, cliente, telefono, fecha, hora, abonado, fotoPath from citas_tatuajes order by id").fetchall()
caso("Los datos originales no cambian", datos == [(f[0], f[1], f[2], f[3], f[4], f[9], f[11]) for f in filas], str(datos))
caso("fechaCobro de la migración 8→9 se conserva", con.execute("select fechaCobro from citas_tatuajes where id='a2'").fetchone()[0] == "20/11/2026")

# esquema final vs entidades
esperado_cita = [(n, t, nn, pk) for n, t, nn, pk in campos_entidad(cita_src, "Cita")]
caso("citas_tatuajes coincide con la entidad Cita (nombres, tipos, NOT NULL, PK)", info(con, "citas_tatuajes") == esperado_cita,
     f"{info(con, 'citas_tatuajes')} vs {esperado_cita}")
esperado_op = campos_entidad(leer("OperacionSync.kt"), "OperacionSync")
caso("operaciones_sync coincide con la entidad OperacionSync", info(con, "operaciones_sync") == esperado_op,
     f"{info(con, 'operaciones_sync')} vs {esperado_op}")
caso("gastos coincide con la entidad Gasto", info(con, "gastos") == campos_entidad(leer("Gasto.kt"), "Gasto"))

ops = con.execute("select idCita, tipo, revision, payload, bloqueada from operaciones_sync order by idCita").fetchall()
caso("Las citas no enviadas (a1, a3) quedan en la cola como 'guardar' rev 1",
     ops == [("a1", "guardar", 1, "", 0), ("a3", "guardar", 1, "", 0)], str(ops))
revs = dict(con.execute("select id, revision from citas_tatuajes").fetchall())
caso("Revisiones: no enviadas = 1, ya enviada = 0", revs == {"a1": 1, "a2": 0, "a3": 1}, str(revs))
ids = [r[0] for r in con.execute("select opId from operaciones_sync")]
caso("Cada operación migrada tiene un opId único", len(set(ids)) == len(ids) and all(len(i) == 32 for i in ids), str(ids))

# ---------- consultas reales de los DAO ----------
Q = {**consultas(leer("CitaDao.kt")), **consultas(leer("OperacionSync.kt"))}
for n in ["revisionDe", "marcarSincronizadaSiRevision", "pendientes", "deCita", "borrarSiOp", "registrarFallo", "contarBloqueadas", "reintentarBloqueadas"]:
    caso(f"Consulta {n} encontrada en el código", n in Q)


def encolar(c, id_cita, tipo, rev, payload=""):   # @Insert(onConflict = REPLACE)
    op = str(uuid.uuid4())
    c.execute("INSERT OR REPLACE INTO operaciones_sync VALUES (?,?,?,?,?,?,?,?,?)", (id_cita, op, tipo, rev, payload, 0, None, 0, 1))
    return op


def guardar_repo(c, id_cita, nombre):             # CitaRepo.guardar (misma transacción)
    with c:
        r1 = ejecutar(c, Q["revisionDe"], id=id_cita).fetchone()
        r2 = ejecutar(c, Q["deCita"], idCita=id_cita).fetchone()
        rev = max(r1[0] if r1 else 0, r2[3] if r2 else 0) + 1
        c.execute("UPDATE citas_tatuajes SET cliente=?, revision=?, sincronizado=0 WHERE id=?", (nombre, rev, id_cita))
        return rev, encolar(c, id_cita, "guardar", rev)


def completar(c, id_cita, op, tipo, rev):           # ColaRoom.completar (misma transacción)
    with c:
        quitada = ejecutar(c, Q["borrarSiOp"], idCita=id_cita, opId=op).rowcount == 1
        marcada = ejecutar(c, Q["marcarSincronizadaSiRevision"], id=id_cita, revision=rev).rowcount if quitada and tipo == "guardar" else 0
        return quitada, marcada


op_a1 = con.execute("select opId from operaciones_sync where idCita='a1'").fetchone()[0]
caso("Completar la operación vigente la quita y marca la cita", completar(con, "a1", op_a1, "guardar", 1) == (True, 1))
caso("…y la cita queda sincronizada", con.execute("select sincronizado from citas_tatuajes where id='a1'").fetchone()[0] == 1)

# carrera: se edita mientras se envía
rev1, op1 = guardar_repo(con, "a1", "Laura G.")
rev2, op2 = guardar_repo(con, "a1", "Laura García")       # edición durante el envío de op1
caso("Editar durante el envío sustituye la operación (una sola por cita)",
     con.execute("select count(*), max(opId) from operaciones_sync where idCita='a1'").fetchone() == (1, op2))
caso("La respuesta tardía de la revisión vieja NO quita la operación nueva ni marca la cita",
     completar(con, "a1", op1, "guardar", rev1) == (False, 0)
     and con.execute("select sincronizado from citas_tatuajes where id='a1'").fetchone()[0] == 0)
caso("La revisión nueva sí se completa", completar(con, "a1", op2, "guardar", rev2) == (True, 1))
caso("marcarSincronizadaSiRevision no marca si la revisión no coincide",
     ejecutar(con, Q["marcarSincronizadaSiRevision"], id="a1", revision=rev1).rowcount == 0)

# borrar en una transacción, con fallo a mitad (rollback) y sin fallo
rev_a3 = con.execute("select revision from citas_tatuajes where id='a3'").fetchone()[0]
try:
    with con:
        encolar(con, "a3", "borrar", rev_a3 + 1, '{"id_cita":"a3"}')
        raise RuntimeError("se apaga el móvil a mitad")
except RuntimeError:
    pass
caso("Si algo falla a mitad del borrado, no queda nada a medias (rollback)",
     con.execute("select count(*) from citas_tatuajes where id='a3'").fetchone()[0] == 1
     and con.execute("select tipo from operaciones_sync where idCita='a3'").fetchone()[0] == "guardar")
with con:
    encolar(con, "a3", "borrar", rev_a3 + 1, '{"id_cita":"a3"}')
    con.execute("DELETE FROM citas_tatuajes WHERE id='a3'")
caso("Borrar: la cita desaparece y queda UNA operación 'borrar' que sustituye al guardado pendiente",
     con.execute("select count(*) from citas_tatuajes where id='a3'").fetchone()[0] == 0
     and con.execute("select tipo, revision from operaciones_sync where idCita='a3'").fetchall() == [("borrar", rev_a3 + 1)])

# fallos: bloquear y desbloquear
op_b = con.execute("select opId from operaciones_sync where idCita='a3'").fetchone()[0]
ejecutar(con, Q["registrarFallo"], idCita="a3", opId=op_b, error="400 fecha_no_valida", bloquear=1)
caso("Una operación bloqueada no sale en pendientes() pero sigue guardada",
     all(r[0] != "a3" for r in ejecutar(con, Q["pendientes"]).fetchall())
     and ejecutar(con, Q["contarBloqueadas"]).fetchone()[0] == 1)
caso("registrarFallo con un opId antiguo no toca la operación vigente",
     ejecutar(con, Q["registrarFallo"], idCita="a3", opId="viejo", error="x", bloquear=0).rowcount == 0)

# al abrir la app se reintentan las bloqueadas con menos de 5 intentos
caso("reintentarBloqueadas desbloquea la operación rechazada (1 intento < 5)",
     ejecutar(con, Q["reintentarBloqueadas"], max=5).rowcount == 1
     and any(r[0] == "a3" for r in ejecutar(con, Q["pendientes"]).fetchall()))
con.execute("UPDATE operaciones_sync SET bloqueada = 1, intentos = 5 WHERE idCita = 'a3'")
caso("…pero no la que ya lleva 5 intentos (no reintenta para siempre)",
     ejecutar(con, Q["reintentarBloqueadas"], max=5).rowcount == 0
     and ejecutar(con, Q["contarBloqueadas"]).fetchone()[0] == 1)

ok = sum(resultados)
print(f"\nRESULTADO Room/SQLite (SQLite {sqlite3.sqlite_version}): {ok}/{len(resultados)} pruebas pasan")
sys.exit(0 if ok == len(resultados) else 1)
