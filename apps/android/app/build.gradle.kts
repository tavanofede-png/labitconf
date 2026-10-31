import java.security.MessageDigest
import java.util.zip.ZipFile
import java.io.File
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
        versionCode = 1
        versionName = "0.1.0-labitconf"
        testInstrumentationRunner = "org.nieto.assistant.presence.VoiceSmokeInstrumentation"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug { manifestPlaceholders["cleartextAllowed"] = "true" }
        release {
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
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/voice-assets"))
}

// Reproducible offline voice model. The archive stays out of Git and ships inside the APK.
val prepareVoiceModel by tasks.registering {
    val checksum = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
    val destination = layout.buildDirectory.dir("generated/voice-assets/model-es")
    inputs.property("modelSha256", checksum)
    outputs.dir(destination)
    doLast {
        val archive = rootProject.file("../../.cache/voice-models/vosk-model-small-es-0.42.zip")
        if (!archive.exists()) {
            archive.parentFile.mkdirs()
            val temporary = File(archive.path + ".part")
            val connection = URI("https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip").toURL().openConnection()
            connection.connectTimeout = 30_000; connection.readTimeout = 120_000
            connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.renameTo(archive)) { "No se pudo guardar el modelo de voz." }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == checksum) { "El modelo de voz no coincide con el SHA-256 esperado." }
        val output = destination.get().asFile.apply { mkdirs() }
        ZipFile(archive).use { zip -> zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
            val relative = entry.name.removePrefix("vosk-model-small-es-0.42/")
            val target = File(output, relative)
            check(target.canonicalPath.startsWith(output.canonicalPath + File.separator))
            target.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        } }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareVoiceModel) }

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
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
