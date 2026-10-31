import java.security.MessageDigest
import java.util.zip.ZipFile
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.nieto.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.nieto.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.0-tino"
        testInstrumentationRunner = "org.nieto.assistant.presence.VoiceSmokeInstrumentation"
        testApplicationId = "org.nieto.assistant.tino.test"
        buildConfigField("String", "API_BASE_URL", "\"${providers.environmentVariable("TINO_API_URL").getOrElse("https://tino-web.vercel.app")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    testBuildType = providers.environmentVariable("TINO_TEST_BUILD_TYPE").getOrElse("release")
    signingConfigs {
        create("production") {
            providers.environmentVariable("TINO_KEYSTORE_FILE").orNull?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("TINO_KEYSTORE_PASSWORD").orNull
            keyAlias = "tino"
            keyPassword = providers.environmentVariable("TINO_KEYSTORE_PASSWORD").orNull
        }
    }
    buildTypes {
        debug { manifestPlaceholders["cleartextAllowed"] = "true" }
        release {
            applicationIdSuffix = ".tino"
            signingConfig = signingConfigs.getByName("production")
            manifestPlaceholders["cleartextAllowed"] = "false"
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/tinoWakeAssets"))
}

val prepareWakeModel by tasks.registering {
    val assetsRoot = layout.buildDirectory.dir("generated/tinoWakeAssets")
    outputs.dir(assetsRoot)
    doLast {
        val expected = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
        val cache = rootProject.projectDir.resolve("../../.cache/wake-model").canonicalFile
        cache.mkdirs()
        val archive = cache.resolve("vosk-model-small-es-0.42.zip")
        if (!archive.exists()) {
            val connection = URI("https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip").toURL().openConnection()
            connection.connectTimeout = 20_000; connection.readTimeout = 120_000
            val temporary = cache.resolve("vosk-model-small-es-0.42.downloading")
            connection.getInputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            check(temporary.renameTo(archive)) { "No se pudo guardar el modelo de voz." }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "El modelo Vosk no coincide con el SHA256 fijado." }
        val output = assetsRoot.get().asFile.canonicalFile
        output.mkdirs()
        ZipFile(archive).use { zip ->
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = output.resolve(entry.name).canonicalFile
                check(target.toPath().startsWith(output.toPath())) { "Ruta inválida en modelo de voz." }
                if (entry.isDirectory) target.mkdirs() else {
                    total += entry.size
                    check(entry.size >= 0 && total <= 200_000_000) { "Modelo de voz demasiado grande." }
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        output.resolve("vosk-model-small-es-0.42/uuid").writeText("vosk-model-small-es-0.42-v1")
    }
}
tasks.named("preBuild") { dependsOn(prepareWakeModel) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.savedstate:savedstate:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
