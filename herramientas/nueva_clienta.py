#!/usr/bin/env python3
"""
Da de alta la agenda de una clienta nueva: crea su carpeta en AgendasClientas con todo lo que la
diferencia de la agenda base. Después, en Android Studio: Sync (elefante) → Build Variants → su código.

Ejemplos:
  python3 herramientas/nueva_clienta.py ES0001 "Uñas Gissel" --oficio Manicura --cobro fijo --tema rosa \\
      --telefono "600 000 000" --instagram "@unas_gissel" --ciudad Pamplona --logo ~/logos/gissel.png

  python3 herramientas/nueva_clienta.py ES0002 "Limpia Yuri" --oficio Limpieza --cobro horas --precio 12 --tema turquesa

La api_key se puede pasar con --api-key o pegarla después en res/values/config.xml de su carpeta
(te la manda el bot ADMIN al aprobar el alta). Esa carpeta NO está en GitHub: es privada.

Crea:
  AgendasClientas/es0001_unas_gissel/
    clienta.properties            applicationId propio (com.agendasvalhalla.es0001): no se pisa con otras
    res/values/config.xml         id_clienta, api_key, país, oficio, cobro, bot de recordatorios
    res/values/strings_clienta.xml  nombre de la app
    res/drawable/marco.png, fondo.png   (con marco_fondo.py; necesita rsvg-convert)
    res/drawable/ic_launcher_background.xml + res/mipmap-*/ic_launcher*.png   icono (su logo o sus iniciales)
"""
import argparse, html, os, re, shutil, sys, tempfile, unicodedata
from datetime import date
from types import SimpleNamespace

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.dirname(AQUI)
CONFIG_BASE = os.path.join(RAIZ, "app", "src", "main", "res", "values", "config.xml")
sys.path.insert(0, AQUI)
import marco_fondo  # noqa: E402

BOTS = {"ES": "Agenda_RecordES_Bot", "CO": "Agenda_RecordCOL_Bot"}
DENSIDADES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def slug(texto):
    t = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "_", t).strip("_")[:30] or "clienta"


def xml_txt(v):
    # texto seguro dentro de un <string> de Android
    return html.escape(v, quote=False).replace("'", "\\'").replace('"', '\\"')


def dir_clientas(arg):
    if arg:
        return os.path.abspath(os.path.expanduser(arg))
    local = os.path.join(RAIZ, "local.properties")
    if os.path.isfile(local):
        for linea in open(local, encoding="utf-8"):
            if linea.strip().startswith("agendas.clientas.dir="):
                return os.path.abspath(os.path.join(RAIZ, linea.split("=", 1)[1].strip()))
    return os.path.abspath(os.path.join(RAIZ, "..", "AgendasClientas"))


def config_xml(a, pais):
    s = open(CONFIG_BASE, encoding="utf-8").read()
    valores = {
        "agenda_pais": pais,
        "agenda_id_clienta": a.codigo,
        "agenda_api_key": a.api_key or "PON_AQUI_LA_API_KEY",
        "agenda_servicio": a.oficio,
        "agenda_modo_cobro": a.cobro,
        "agenda_precio_hora": a.precio,
        "agenda_bot_recordatorios": BOTS[pais],
    }
    for nombre, valor in valores.items():
        s, n = re.subn(rf'(<string name="{nombre}"[^>]*>)(.*?)(</string>)', lambda m: m.group(1) + xml_txt(valor) + m.group(3), s)
        if n != 1:
            sys.exit(f"No encuentro {nombre} en {CONFIG_BASE}")
    cabecera = f"<!-- Agenda de {a.nombre} ({a.codigo}) · generada el {date.today():%d/%m/%Y} con herramientas/nueva_clienta.py -->\n"
    return s.replace("<resources>", cabecera + "<resources>", 1)


def iconos(a, tema, res):
    from PIL import Image, ImageDraw, ImageFont, ImageOps
    f1, f2, ac, tx, _ = tema
    hexrgb = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
    fondo_rgb = hexrgb(f2 if a.tema != "valhalla" else "#0b1118")
    os.makedirs(os.path.join(res, "drawable"), exist_ok=True)
    open(os.path.join(res, "drawable", "ic_launcher_background.xml"), "w", encoding="utf-8").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" '
        f'android:shape="rectangle">\n    <solid android:color="{f2}"/>\n</shape>\n')

    def contenido(lado):
        """Logo (o iniciales) en un lienzo transparente cuadrado."""
        lienzo = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
        if a.logo:
            logo = Image.open(os.path.expanduser(a.logo)).convert("RGBA")
            logo = ImageOps.contain(logo, (lado, lado), Image.LANCZOS)
            lienzo.paste(logo, ((lado - logo.width) // 2, (lado - logo.height) // 2), logo)
        else:
            iniciales = "".join(p[0] for p in re.findall(r"\w+", a.nombre)[:2]).upper() or "A"
            d = ImageDraw.Draw(lienzo)
            d.ellipse((0, 0, lado - 1, lado - 1), outline=hexrgb(ac), width=max(2, lado // 28))
            fuente = None
            for f in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/opentype/inter/Inter-Bold.otf",
                      "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"]:
                if os.path.isfile(f):
                    fuente = ImageFont.truetype(f, int(lado * 0.42)); break
            fuente = fuente or ImageFont.load_default()
            d.text((lado / 2, lado / 2), iniciales, font=fuente, fill=hexrgb(tx), anchor="mm")
        return lienzo

    for dens, k in DENSIDADES.items():
        carpeta = os.path.join(res, f"mipmap-{dens}")
        os.makedirs(carpeta, exist_ok=True)
        # icono adaptativo (Android 8+): 108 dp, el contenido en la zona segura central (~60 %)
        fg_lado = int(108 * k)
        fg = Image.new("RGBA", (fg_lado, fg_lado), (0, 0, 0, 0))
        c = contenido(int(fg_lado * 0.58))
        fg.paste(c, ((fg_lado - c.width) // 2, (fg_lado - c.height) // 2), c)
        fg.save(os.path.join(carpeta, "ic_launcher_foreground.png"))
        # iconos clásicos (Android 7): 48 dp, cuadrado y redondo
        lado = int(48 * k)
        base = Image.new("RGBA", (lado, lado), fondo_rgb + (255,))
        c = contenido(int(lado * 0.78))
        base.paste(c, ((lado - c.width) // 2, (lado - c.height) // 2), c)
        base.save(os.path.join(carpeta, "ic_launcher.png"))
        mascara = Image.new("L", (lado, lado), 0)
        ImageDraw.Draw(mascara).ellipse((0, 0, lado - 1, lado - 1), fill=255)
        redondo = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
        redondo.paste(base, (0, 0), mascara)
        redondo.save(os.path.join(carpeta, "ic_launcher_round.png"))


def marco_y_fondo(a, tema, res):
    if not shutil.which("rsvg-convert"):
        print("⚠️  No está rsvg-convert (sudo apt install librsvg2-bin): marco y fondo se quedan los de la base.")
        return False
    datos = SimpleNamespace(titulo=a.titulo, nombre=a.nombre, eslogan=a.eslogan or a.oficio, cta="Pide tu cita",
                            telefono=a.telefono, instagram=a.instagram, ciudad=a.ciudad,
                            logo=os.path.expanduser(a.logo) if a.logo else "", tema=a.tema)
    drawable = os.path.join(res, "drawable")
    os.makedirs(drawable, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        marco_fondo.render(marco_fondo.marco(datos, tema), os.path.join(tmp, "marco.png"), marco_fondo.S)
        marco_fondo.render(marco_fondo.fondo(datos, tema), os.path.join(tmp, "fondo.png"), 1080)
        for f in ("marco.png", "fondo.png"):
            shutil.move(os.path.join(tmp, f), os.path.join(drawable, f))
    return True


def main(argv=None):
    p = argparse.ArgumentParser(description="Crea la carpeta de una clienta nueva en AgendasClientas.")
    p.add_argument("codigo", help="id_clienta: ES0001, CO0003…")
    p.add_argument("nombre", help="nombre del negocio (sale como nombre de la app y en el marco)")
    p.add_argument("--oficio", default="Cita", help="Manicura, Tatuaje, Limpieza… (texto de los recordatorios)")
    p.add_argument("--cobro", choices=["horas", "fijo"], default="fijo")
    p.add_argument("--precio", default="12", help="precio por hora inicial (modo horas)")
    p.add_argument("--api-key", default="", help="la api_key que manda el bot ADMIN (se puede poner después)")
    p.add_argument("--tema", choices=list(marco_fondo.TEMAS), default="valhalla")
    p.add_argument("--logo", default="", help="PNG/JPG del logo de la clienta (icono, marco y fondo)")
    p.add_argument("--titulo", default="Trabajo realizado")
    p.add_argument("--eslogan", default="")
    p.add_argument("--telefono", default="")
    p.add_argument("--instagram", default="")
    p.add_argument("--ciudad", default="")
    p.add_argument("--dir", default="", help="carpeta AgendasClientas (por defecto, al lado del proyecto)")
    p.add_argument("--forzar", action="store_true", help="sobrescribir si la clienta ya existe")
    a = p.parse_args(argv)

    a.codigo = a.codigo.strip().upper()
    if not re.fullmatch(r"(ES|CO)\d{4}", a.codigo):
        sys.exit("El código debe ser ES o CO + 4 cifras (ES0001, CO0003…)")
    pais = a.codigo[:2]
    if not re.fullmatch(r"\d+([.,]\d{1,2})?", a.precio):
        sys.exit("--precio debe ser un número (12 o 12,50)")
    a.precio = a.precio.replace(",", ".")
    if a.api_key and not re.fullmatch(r"[A-Za-z0-9_-]{16,}", a.api_key):
        sys.exit("--api-key no parece válida (mínimo 16 letras/números, sin espacios)")
    if a.logo and not os.path.isfile(os.path.expanduser(a.logo)):
        sys.exit(f"No encuentro el logo: {a.logo}")

    base = dir_clientas(a.dir)
    existentes = [d for d in (os.listdir(base) if os.path.isdir(base) else []) if d.lower().startswith(a.codigo.lower() + "_")]
    if existentes and not a.forzar:
        sys.exit(f"Ya existe {existentes[0]} en {base}. Usa --forzar para rehacerla.")
    for d in existentes:
        shutil.rmtree(os.path.join(base, d))
    carpeta = os.path.join(base, f"{a.codigo.lower()}_{slug(a.nombre)}")
    res = os.path.join(carpeta, "res")
    os.makedirs(os.path.join(res, "values"), exist_ok=True)

    with open(os.path.join(carpeta, "clienta.properties"), "w", encoding="utf-8") as f:
        f.write(f"# Agenda de {a.nombre}\n# Este applicationId debe ser único: dos clientas con el mismo se pisarían en el móvil.\n")
        f.write(f"applicationId=com.agendasvalhalla.{a.codigo.lower()}\n")
        f.write(f"codigo={a.codigo}\nnombre={a.nombre}\npais={pais}\ntema={a.tema}\ncreada={date.today().isoformat()}\n")
    open(os.path.join(res, "values", "config.xml"), "w", encoding="utf-8").write(config_xml(a, pais))
    open(os.path.join(res, "values", "strings_clienta.xml"), "w", encoding="utf-8").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <string name="app_name">{xml_txt(a.nombre)}</string>\n</resources>\n')
    tema = marco_fondo.TEMAS[a.tema]
    iconos(a, tema, res)
    hay_marco = marco_y_fondo(a, tema, res)

    print(f"✅ Clienta creada en {carpeta}")
    print(f"   Variante: {a.codigo.lower()}  ·  paquete: com.agendasvalhalla.{a.codigo.lower()}  ·  país: {pais}  ·  cobro: {a.cobro}")
    print(f"   Marco y fondo: {'generados' if hay_marco else 'los de la base'}  ·  icono: {'su logo' if a.logo else 'sus iniciales'}")
    if not a.api_key:
        print(f"   ⚠️  Falta la api_key: pégala en {os.path.join(res, 'values', 'config.xml')}")
    print("   Siguiente: Android Studio → Sync (elefante) → Build Variants → " + a.codigo.lower() + "Debug → ▶️")
    return carpeta


if __name__ == "__main__":
    main()
