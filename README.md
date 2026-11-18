# agendasvalhalla
⚔️ Agendas Valhalla — Custom-branded appointment app for independent professionals (tattoo, nails, cleaning, care). Android + n8n + Supabase + local AI. · Agenda profesional personalizada para autónomas. 🇪🇸🇨🇴
🇬🇧 English
What is it?

Agendas Valhalla is a white-label appointment app sold as a subscription to independent professionals: tattoo artists, nail technicians, beauticians, cleaners, caregivers and nannies.

It started as a personal project: an agenda built for my wife (tattoo artist) and my sister-in-law (nail technician). It worked so well that it became a small SaaS for Spain and Colombia.

Every professional gets her own app with her own brand (logo, colours, background and photo frame), and the backend automates the boring parts: reminders, confirmations, payments and support.

Features

In the app (Básico plan)

📅 Appointments with local reminder before each one
💶 Two billing modes: per hour (hours × hourly rate) or fixed price per job
⏳ Paid / pending tracking, partial payments and a "Pending payments" screen
📸 Photo of the work with the professional's own frame, ready for Instagram
💬 One-tap WhatsApp reminder to the customer
📴 Works offline; syncs when there is a connection

Automated (Pro plan)

🔔 Automatic reminders to end customers 24 h before (Telegram, or email as fallback)
✅❌ Customers confirm or cancel with one tap; the status shows up in the app
📩 Cancellation alerts to the professional
🤖 24 h support assistant (local LLM) with human approval before answering

Business back office

Sign-up form → approval in Telegram → welcome email
Manual payment registration (Bizum / Nequi), receipts and monthly fee reminders
Weekly business summary in Telegram
Fully separated data and flows per country 🇪🇸 / 🇨🇴
Architecture
☁️ Cloud
🖥️ Self-hosted (Docker + Tailscale Funnel)
📱 Professional's phone
webhooks (per-client APIkey)
statuses
reminder + ✅/❌
Android appKotlin · Compose · Room
n8nbusiness · app engine ·support · email
LM StudioLlama 3.1 8B
Supabase 🇪🇸
Supabase 🇨🇴
Gmail
Telegram botsadmin · support · reminders
👤 End customer
Tech stack
Layer	Technology
Mobile app	Kotlin, Jetpack Compose, Room (with migrations), CameraX, HttpURLConnection
Automations	n8n (self-hosted in Docker), webhooks, schedules, Telegram send-and-wait approvals
Database	Supabase Postgres (one project per country), RLS enabled, views for reminders and summaries
Messaging	Telegram Bot API (deep-link onboarding), Gmail
AI	LM Studio + Llama 3.1 8B, JSON-schema structured output, human-in-the-loop
Hosting / network	Ubuntu, Docker, Tailscale Funnel
Web	Static site + privacy policy on GitHub Pages
Repository structure
├── android/            # Base Android app (one build per client: colours, logo, frame, config.xml)
├── n8n/
│   ├── ES_espana/      # Spain flows: 01 business · 02 app engine · 03 support agent
│   ├── CO_colombia/    # Colombia flows (same structure)
│   └── COMUN_correo/   # Shared email support agent (detects the country)
├── supabase/           # SQL schemas (ES / CO)
├── web/                # Landing page and privacy policy
└── assets/             # Logo and brand images
Setup (short version)
Create the Supabase projects and run the SQL in supabase/.
Import the n8n flows and create the credentials (Postgres via Session pooler, Telegram, Gmail, Google Sheets).
Replace the TU_... placeholders (sheet IDs, admin chat ID, payment password, form links).
For each client, fill in android/app/src/main/res/values/config.xml (country, client ID, API key, billing mode, rate), set her colours, logo and frame, and build the APK.

⚠️ No secrets live in this repository. Bot tokens, database passwords and API keys belong only in n8n credentials or in each client's private build.

Roadmap
[ ] Automatic Bizum / Nequi payment detection (phone notification → n8n → approval)
[ ] Card payments with Stripe
[ ] WhatsApp Business reminders (especially for Colombia)
[ ] iOS version
🇪🇸 Español
¿Qué es?

Agendas Valhalla es una agenda de citas con la marca de cada profesional, que se ofrece por suscripción a tatuadoras, manicuristas, esteticistas, limpiadoras, cuidadoras y niñeras.

Empezó como un proyecto personal: una agenda para mi mujer (tatuadora) y mi cuñada (manicurista). Funcionó tan bien que se ha convertido en un pequeño SaaS para España y Colombia.

Cada profesional tiene su propia app con su marca (logo, colores, fondo y marco de foto), y el backend automatiza lo pesado: recordatorios, confirmaciones, pagos y soporte.

Funciones

En la app (plan Básico)

📅 Citas con aviso en el móvil antes de cada una
💶 Dos formas de cobrar: por horas (horas × precio/hora) o precio cerrado por trabajo
⏳ Control de lo pagado y lo pendiente, pagos parciales y pantalla de cobros
📸 Foto del trabajo con su propio marco, lista para Instagram
💬 Recordatorio por WhatsApp al cliente con un toque
📴 Funciona sin conexión y sincroniza cuando vuelve

Automatizado (plan Pro)

🔔 Recordatorios automáticos a los clientes 24 h antes (Telegram, o correo si no lo tienen)
✅❌ El cliente confirma o cancela con un toque y la profesional lo ve en la app
📩 Aviso a la profesional si alguien cancela
🤖 Asistente de soporte 24 h con IA local, con aprobación humana antes de responder

Gestión del negocio

Formulario de alta → aprobación por Telegram → correo de bienvenida
Registro de pagos (Bizum / Nequi), recibos y recordatorios de cuota
Resumen semanal del negocio por Telegram
Datos y flujos totalmente separados por país 🇪🇸 / 🇨🇴
Arquitectura y tecnología

La arquitectura es la del diagrama de la sección en inglés:

App Android: Kotlin + Compose + Room.
Automatizaciones: n8n en Docker, publicado con Tailscale Funnel.
Base de datos: Supabase, un proyecto por país.
Mensajería: bots de Telegram y Gmail.
IA: LM Studio con Llama 3.1 8B en local.
Puesta en marcha (resumen)
Crea los proyectos de Supabase y ejecuta los SQL de supabase/.
Importa los flujos de n8n y crea las credenciales (Postgres por Session pooler, Telegram, Gmail, Google Sheets).
Sustituye los marcadores TU_... (IDs de hojas, chat del admin, clave de pagos, enlaces de formularios).
Para cada clienta, rellena config.xml (país, id de clienta, api key, modo de cobro, precio), pon sus colores, su logo y su marco, y compila la APK.

⚠️ En este repositorio no hay ninguna clave. Los tokens de los bots, las contraseñas de la base de datos y las API keys van solo en las credenciales de n8n o en la compilación privada de cada clienta.

Próximos pasos
[ ] Detección automática de pagos Bizum / Nequi (notificación del móvil → n8n → aprobación)
[ ] Pago con tarjeta mediante Stripe
[ ] Recordatorios por WhatsApp Business (sobre todo para Colombia)
[ ] Versión para iOS
