"""
Generador de MARCO (foto de trabajo) y FONDO para las agendas.

Uso rápido (marco y fondo base de Agendas Valhalla):
    python3 marco_fondo.py

Para una clienta:
    python3 marco_fondo.py --nombre "Uñas María" --eslogan "Manicura y nail art" \
        --telefono "600 000 000" --instagram "@unas_maria" --ciudad "Pamplona" \
        --logo logo_maria.png --tema rosa --salida maria

Salida: <salida>/marco.png (1254×1254, ventana transparente) y <salida>/fondo.png (1080×1920).
Se copian a app/src/main/res/drawable/ reemplazando los que hay.
"""
import argparse, base64, math, os, random, subprocess, html

TEMAS = {
    # fondo1 (centro), fondo2 (bordes), acento, texto, texto suave
    "valhalla": ("#17344c", "#070c12", "#4cc9ff", "#eef4f9", "#9fb2c3"),
    "rosa":     ("#5a1f3d", "#1e0a15", "#ff8fc7", "#fff1f8", "#e3b2ca"),
    "turquesa": ("#11605f", "#06201f", "#f2c46d", "#fffaf0", "#cfe7e3"),
    "oro":      ("#2a2116", "#0b0906", "#d6b26a", "#fbf3e2", "#c9b48a"),
    "lavanda":  ("#3d2f63", "#120d22", "#c7b2ff", "#f6f2ff", "#bfb3df"),
}

S = 1254
VENTANA = (96, 196, 1158, 966)   # x1, y1, x2, y2 de la zona de la foto
RADIO = 26


def esc(t):
    return html.escape(t, quote=True)


def defs(t):
    f1, f2, ac, tx, ts = t
    return f"""<defs>
  <radialGradient id="bg" cx=".5" cy=".42" r=".8"><stop offset="0" stop-color="{f1}"/><stop offset="1" stop-color="{f2}"/></radialGradient>
  <linearGradient id="lin" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{ac}" stop-opacity="0"/><stop offset=".5" stop-color="{ac}"/><stop offset="1" stop-color="{ac}" stop-opacity="0"/></linearGradient>
  <filter id="glow" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="5" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  <filter id="blur" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="40"/></filter>
  <mask id="hueco"><rect width="{S}" height="{S}" fill="white"/>
    <rect x="{VENTANA[0]}" y="{VENTANA[1]}" width="{VENTANA[2]-VENTANA[0]}" height="{VENTANA[3]-VENTANA[1]}" rx="{RADIO}" fill="black"/></mask>
  <clipPath id="clipLogo"><circle cx="0" cy="0" r="104"/></clipPath>
</defs>"""


def rombo(x, y, r, color, op=1):
    return f'<rect x="{x-r}" y="{y-r}" width="{2*r}" height="{2*r}" transform="rotate(45 {x} {y})" fill="{color}" opacity="{op}"/>'


def destello(x, y, r, color):
    p = f"M{x},{y-r} Q{x},{y} {x+r},{y} Q{x},{y} {x},{y+r} Q{x},{y} {x-r},{y} Q{x},{y} {x},{y-r}Z"
    return f'<path d="{p}" fill="{color}" filter="url(#glow)"/>'


def esquina(x, y, sx, sy, ac):
    """Adorno de esquina: dos trazos en L con rombo (estilo nudo simple)."""
    d = f"M{x+sx*10},{y+sy*120} L{x+sx*10},{y+sy*10} L{x+sx*120},{y+sy*10}"
    d2 = f"M{x+sx*28},{y+sy*90} L{x+sx*28},{y+sy*28} L{x+sx*90},{y+sy*28}"
    return (f'<path d="{d}" fill="none" stroke="{ac}" stroke-width="5"/>'
            f'<path d="{d2}" fill="none" stroke="{ac}" stroke-width="2.5" opacity=".7"/>'
            + rombo(x + sx * 10, y + sy * 10, 9, ac))


def icono(tipo, x, y, ac):
    st = f'fill="none" stroke="{ac}" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"'
    if tipo == "tel":
        return (f'<g {st}><path d="M{x-9},{y-14} q-6,0 -6,6 q2,22 26,26 q6,0 6,-6 v-6 l-9,-4 -5,5 q-9,-4 -13,-13 l5,-5 -4,-9 z"/></g>')
    if tipo == "ig":
        return (f'<g {st}><rect x="{x-15}" y="{y-15}" width="30" height="30" rx="9"/><circle cx="{x}" cy="{y}" r="7"/>'
                f'<circle cx="{x+9}" cy="{y-9}" r="1.5" fill="{ac}"/></g>')
    if tipo == "pin":
        return (f'<g {st}><path d="M{x},{y+17} C{x-16},{y} {x-14},{y-17} {x},{y-17} C{x+14},{y-17} {x+16},{y} {x},{y+17}z"/>'
                f'<circle cx="{x}" cy="{y-4}" r="5"/></g>')
    return ""


def logo_svg(cx, cy, ruta, t):
    f1, f2, ac, tx, ts = t
    s = (f'<circle cx="{cx}" cy="{cy}" r="118" fill="{f2}" stroke="{ac}" stroke-width="6"/>'
         f'<circle cx="{cx}" cy="{cy}" r="108" fill="none" stroke="{ac}" stroke-width="2" opacity=".6"/>')
    if ruta and os.path.exists(ruta):
        mime = "image/png" if ruta.lower().endswith(".png") else "image/jpeg"
        b64 = base64.b64encode(open(ruta, "rb").read()).decode()
        s += (f'<g transform="translate({cx},{cy})" clip-path="url(#clipLogo)">'
              f'<image href="data:{mime};base64,{b64}" x="-104" y="-104" width="208" height="208" preserveAspectRatio="xMidYMid slice"/></g>')
    else:
        s += (f'<text x="{cx}" y="{cy-6}" text-anchor="middle" font-family="Inter" font-weight="700" font-size="34" fill="{tx}" letter-spacing="3">TU</text>'
              f'<text x="{cx}" y="{cy+34}" text-anchor="middle" font-family="Inter" font-weight="700" font-size="34" fill="{tx}" letter-spacing="3">LOGO</text>')
    for a in range(0, 360, 45):
        x = cx + 132 * math.cos(math.radians(a)); y = cy + 132 * math.sin(math.radians(a))
        s += rombo(x, y, 5, ac, .9)
    return s


def marco(a, t):
    f1, f2, ac, tx, ts = t
    x1, y1, x2, y2 = VENTANA
    s = f'<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" viewBox="0 0 {S} {S}">{defs(t)}'
    s += '<g mask="url(#hueco)">'
    s += f'<rect width="{S}" height="{S}" fill="url(#bg)"/>'
    # brillo suave de acento
    s += f'<ellipse cx="{S/2}" cy="90" rx="520" ry="120" fill="{ac}" opacity=".10" filter="url(#blur)"/>'
    # destellos y puntos
    random.seed(11)
    for _ in range(60):
        x, y = random.randint(20, S - 20), random.randint(20, S - 20)
        if x1 - 20 < x < x2 + 20 and y1 - 20 < y < y2 + 20:
            continue
        s += f'<circle cx="{x}" cy="{y}" r="{random.choice([1.2, 1.8, 2.5])}" fill="{tx}" opacity="{random.uniform(.25, .7):.2f}"/>'
    for (x, y, r) in [(62, 330, 16), (1192, 300, 20), (60, 640, 12), (1194, 700, 14)]:
        s += destello(x, y, r, ac)
    # bordes exteriores
    s += f'<rect x="14" y="14" width="{S-28}" height="{S-28}" rx="30" fill="none" stroke="{ac}" stroke-width="5"/>'
    s += f'<rect x="30" y="30" width="{S-60}" height="{S-60}" rx="22" fill="none" stroke="{ac}" stroke-width="1.8" opacity=".55"/>'
    for (x, y, sx, sy) in [(30, 30, 1, 1), (S - 30, 30, -1, 1)]:
        s += esquina(x, y, sx, sy, ac)
    # título
    s += (f'<text x="{S/2}" y="128" text-anchor="middle" font-family="Inter" font-weight="800" font-size="74" '
          f'letter-spacing="10" fill="{tx}">{esc(a.titulo.upper())}</text>')
    s += f'<rect x="{S/2-330}" y="152" width="660" height="3" fill="url(#lin)"/>' + rombo(S / 2, 153, 8, ac)
    s += '</g>'
    # marco de la ventana (encima, fuera de la máscara para que se vea el filo)
    s += f'<rect x="{x1-6}" y="{y1-6}" width="{x2-x1+12}" height="{y2-y1+12}" rx="{RADIO+6}" fill="none" stroke="{ac}" stroke-width="6"/>'
    s += f'<rect x="{x1-16}" y="{y1-16}" width="{x2-x1+32}" height="{y2-y1+32}" rx="{RADIO+14}" fill="none" stroke="{ac}" stroke-width="1.6" opacity=".6"/>'
    for (x, y) in [(x1 - 6, y1 - 6), (x2 + 6, y1 - 6), (x1 - 6, y2 + 6), (x2 + 6, y2 + 6)]:
        s += rombo(x, y, 11, ac)
    # banda inferior
    yb = y2 + 30
    s += (f'<text x="74" y="{yb+78}" font-family="Inter" font-weight="800" font-size="62" fill="{tx}">{esc(a.nombre)}</text>'
          f'<text x="76" y="{yb+124}" font-family="Inter" font-weight="400" font-size="30" fill="{ts}">{esc(a.eslogan)}</text>')
    s += f'<rect x="74" y="{yb+150}" width="420" height="3" fill="{ac}" opacity=".8"/>'
    s += (f'<text x="76" y="{yb+200}" font-family="Inter" font-weight="700" font-size="26" letter-spacing="4" fill="{ac}">'
          f'{esc(a.cta.upper())}</text>')
    # contacto
    cx0, cy0 = 640, yb + 52
    filas = [("tel", a.telefono), ("ig", a.instagram), ("pin", a.ciudad)]
    filas = [f for f in filas if f[1]]
    for i, (ic, txt) in enumerate(filas):
        y = cy0 + i * 62
        s += icono(ic, cx0, y, ac)
        s += f'<text x="{cx0+34}" y="{y+11}" font-family="Inter" font-weight="500" font-size="32" fill="{tx}">{esc(txt)}</text>'
    s += f'<line x1="{cx0-36}" y1="{yb+14}" x2="{cx0-36}" y2="{yb+196}" stroke="{ac}" stroke-width="2" opacity=".6"/>'
    # logo
    s += logo_svg(1046, yb + 104, a.logo, t)
    s += '</svg>'
    return s


def fondo(a, t):
    f1, f2, ac, tx, ts = t
    W, H = 1080, 1920
    s = f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">{defs(t)}'
    s += f'<rect width="{W}" height="{H}" fill="url(#bg)"/>'
    # aurora
    s += f'<g filter="url(#blur)" opacity=".55"><path d="M-120,420 C200,260 420,560 700,360 S1000,250 1200,380" stroke="{ac}" stroke-width="90" fill="none"/>'
    s += f'<path d="M-120,560 C240,430 520,700 820,500 S1080,470 1200,540" stroke="{tx}" stroke-width="40" fill="none" opacity=".35"/></g>'
    random.seed(5)
    for _ in range(140):
        x, y = random.randint(0, W), random.randint(0, 1250)
        s += f'<circle cx="{x}" cy="{y}" r="{random.choice([1, 1.4, 2, 2.6])}" fill="{tx}" opacity="{random.uniform(.2, .8):.2f}"/>'
    for (x, y, r) in [(180, 260, 16), (900, 180, 20), (760, 820, 12), (240, 980, 10)]:
        s += destello(x, y, r, ac)
    # montañas
    s += (f'<polygon points="0,1500 160,1320 260,1400 420,1180 560,1380 660,1300 760,1420 900,1240 1080,1450 1080,{H} 0,{H}" fill="{f2}" opacity=".85"/>'
          f'<polygon points="0,1640 220,1500 380,1580 560,1460 760,1600 940,1520 1080,1600 1080,{H} 0,{H}" fill="{f2}"/>')
    # logo de la clienta como marca de agua (si hay)
    if a.logo and os.path.exists(a.logo):
        mime = "image/png" if a.logo.lower().endswith(".png") else "image/jpeg"
        b64 = base64.b64encode(open(a.logo, "rb").read()).decode()
        s += f'<image href="data:{mime};base64,{b64}" x="290" y="660" width="500" height="500" opacity=".18" preserveAspectRatio="xMidYMid meet"/>'
    else:
        s += f'<circle cx="540" cy="900" r="230" fill="none" stroke="{ac}" stroke-width="6" opacity=".35"/>'
        s += f'<circle cx="540" cy="900" r="206" fill="none" stroke="{ac}" stroke-width="2" opacity=".25"/>'
        for k in range(8):
            ang = math.radians(k * 45)
            s += rombo(540 + 250 * math.cos(ang), 900 + 250 * math.sin(ang), 7, ac, .45)
    s += '</svg>'
    return s


def render(svg_txt, ruta, ancho):
    tmp = ruta.replace(".png", ".svg")
    open(tmp, "w").write(svg_txt)
    subprocess.run(["rsvg-convert", "-w", str(ancho), tmp, "-o", ruta], check=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--titulo", default="Trabajo realizado")
    p.add_argument("--nombre", default="Tu Negocio")
    p.add_argument("--eslogan", default="Tu especialidad aquí")
    p.add_argument("--cta", default="Pide tu cita")
    p.add_argument("--telefono", default="600 000 000")
    p.add_argument("--instagram", default="@tu_instagram")
    p.add_argument("--ciudad", default="Tu ciudad")
    p.add_argument("--logo", default="")
    p.add_argument("--tema", default="valhalla", choices=TEMAS.keys())
    p.add_argument("--salida", default="base")
    a = p.parse_args()
    t = TEMAS[a.tema]
    os.makedirs(a.salida, exist_ok=True)
    render(marco(a, t), f"{a.salida}/marco.png", S)
    render(fondo(a, t), f"{a.salida}/fondo.png", 1080)
    print("ok →", a.salida)
