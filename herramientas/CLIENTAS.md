# Agendas por clienta (variantes)

Hay **un solo código** (este proyecto, en GitHub) y una **carpeta privada por clienta** con lo que la hace suya.
Si se arregla un fallo en el código, basta con volver a compilar cada variante.

```
~/AndroidStudioProjects/
├── AgendaBase/            ← este proyecto (GitHub). No se copia ni se toca por clienta.
└── AgendasClientas/       ← PRIVADA (fuera de GitHub: lleva las api_key)
    ├── es0001_unas_gissel/
    │   ├── clienta.properties          applicationId propio → no se pisa con otras agendas
    │   └── res/
    │       ├── values/config.xml       id_clienta, api_key, país, oficio, cobro, bot de recordatorios
    │       ├── values/strings_clienta.xml   nombre de la app
    │       ├── drawable/marco.png, fondo.png
    │       └── mipmap-*/ic_launcher*.png    icono
    └── co0002_limpia_yuri/ …
```

**Regla:** lo que hay en `res/` de la clienta **sustituye** a lo de `app/src/main/res`. Lo que no esté, se toma de la base.
Si quieres otra ubicación, añádela en `local.properties`: `agendas.clientas.dir=/ruta/a/AgendasClientas`.

## Dar de alta una clienta

1. Aprueba su alta en Telegram. El bot ADMIN te manda su **id_clienta** y su **api_key**.
2. Desde la carpeta del proyecto:
   ```bash
   python3 herramientas/nueva_clienta.py ES0001 "Uñas Gissel" --oficio Manicura --cobro fijo --tema rosa \
       --telefono "600 000 000" --instagram "@unas_gissel" --ciudad Pamplona \
       --logo ~/logos/gissel.png --api-key LA_CLAVE_DEL_BOT
   ```
   - **Cobro:** `--cobro horas --precio 12` para limpiadoras y cuidadoras; `--cobro fijo` para tatuadoras y manicuristas.
   - **Temas:** `valhalla`, `rosa`, `turquesa`, `oro` y `lavanda`.
   - **Sin `--logo`:** el icono lleva sus iniciales.
   - **Sin `--api-key`:** el script te dice en qué archivo pegarla después.
   - **Requisitos:** `python3` con Pillow; para el marco y el fondo, `rsvg-convert` (`sudo apt install librsvg2-bin`).
3. En Android Studio, pulsa el **elefante** (Sync), abre **Build Variants** (abajo a la izquierda) y en `app` elige **`es0001Debug`**. Después, ▶️ o **Build → Build APK**.

La variante **`baseDebug`** es la agenda sin clienta, para desarrollar y probar.

## Cambiar algo de una clienta

- **Sus datos** (precio, oficio, api_key…): edita `AgendasClientas/<su carpeta>/res/values/config.xml`.
- **Su marco, su fondo o su icono:** vuelve a lanzar el script con `--forzar` y los datos nuevos, o sustituye los PNG a mano.
- Después: Sync y compilar su variante.

## Importante

- **No borres ni cambies** el `applicationId` de una clienta que ya tiene la app instalada. Para Android sería otra app distinta y empezaría sin sus citas.
- **Haz copia de seguridad** de `AgendasClientas` (Drive, un disco…), porque no está en GitHub.
- **Antes de instalar encima de una agenda antigua** hecha con copia-pega (por ejemplo, la de tu mujer), su `applicationId` y su base de datos tienen que coincidir. En ese caso, usa en su `clienta.properties` el `applicationId` que ya tenía, para que conserve sus citas.
