import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ─────────────────────────────────────────────────────────────────────────────
// 👩‍🎨 Una variante de la app por clienta (Build Variants en Android Studio)
//
// Cada carpeta de ../AgendasClientas (fuera de GitHub) con un clienta.properties es una clienta:
//   AgendasClientas/es0001_unas_gissel/clienta.properties   → applicationId=com.agendasvalhalla.es0001
//   AgendasClientas/es0001_unas_gissel/res/...              → su config.xml, nombre, marco, fondo, icono
// Lo que hay en su res/ SUSTITUYE a lo de app/src/main/res; lo que no está, se usa el de la base.
// La variante se llama como el código (es0001). La variante "base" es la agenda sin clienta.
// Otra ruta: en local.properties →  agendas.clientas.dir=/ruta/a/AgendasClientas
// Para crear una clienta: python3 herramientas/nueva_clienta.py (ver herramientas/CLIENTAS.md)
// ─────────────────────────────────────────────────────────────────────────────
val dirClientas: File = run {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { local.load(it) }
    rootProject.file(local.getProperty("agendas.clientas.dir") ?: "../AgendasClientas")
}

data class Clienta(val variante: String, val carpeta: File, val applicationId: String)

val clientas: List<Clienta> = (dirClientas.listFiles() ?: emptyArray())
    .filter { it.isDirectory && File(it, "clienta.properties").isFile }
    .sortedBy { it.name }
    .map { carpeta ->
        val p = Properties()
        File(carpeta, "clienta.properties").reader(Charsets.UTF_8).use { p.load(it) }
        val variante = carpeta.name.substringBefore("_").lowercase()
        require(Regex("^(es|co)\\d{4}$").matches(variante)) {
            "La carpeta ${carpeta.name} debe empezar por el código de la clienta (es0001_..., co0001_...)"
        }
        val appId = p.getProperty("applicationId")?.trim().orEmpty()
        require(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(appId)) {
            "${carpeta.name}/clienta.properties: applicationId no válido ('$appId')"
        }
        Clienta(variante, carpeta, appId)
    }
    .also { lista ->
        val repetidos = lista.groupBy { it.applicationId }.filterValues { it.size > 1 }.keys
        require(repetidos.isEmpty()) { "Dos clientas comparten applicationId: $repetidos (se pisarían en el móvil)" }
    }

android {
    namespace = "com.example.agendatatuajes"
    compileSdk = 37 // Actualizado para soportar las últimas versiones de las librerías de AndroidX

    defaultConfig {
        applicationId = "com.example.agendatatuajes"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "clienta"
    productFlavors {
        create("base") {
            dimension = "clienta"   // la agenda base, con el config.xml de app/src/main (PON_AQUI_...)
        }
        clientas.forEach { c ->
            create(c.variante) {
                dimension = "clienta"
                applicationId = c.applicationId
            }
        }
    }
    sourceSets {
        clientas.forEach { c -> getByName(c.variante).res.srcDir(File(c.carpeta, "res")) }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.material.icons.extended)

    // 🗄️ Librerías de Room en versión estable compatible con Java 11
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler) // 🔥 Cambiado annotationProcessor por ksp
    
    // 🔁 WorkManager: reenvía a n8n lo pendiente aunque la app esté cerrada
    implementation(libs.androidx.work.runtime.ktx)

    // 🖼️ Coil para cargar imágenes
    implementation(libs.coil.compose)

    // 📸 CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    androidTestImplementation(bom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
