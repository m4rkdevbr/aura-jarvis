import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.dzz.copilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dzz.copilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The dedicated phone (Galaxy Note 10+) is arm64; this drops the other native libraries.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/{LICENSE.md,NOTICE.md,LICENSE,NOTICE,mimetypes.default,mailcap.default,javamail.providers,javamail.default.providers,javamail.default.address.map}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The project path contains spaces and "Área". The test worker then fails to
// load classes from the raw classpath. A jar on an ASCII path, with percent-encoded
// Class-Path entries, keeps those paths intact.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val classpathJar = File(System.getProperty("java.io.tmpdir"), "dzz-test-classpath/$name.jar")
    doFirst {
        val entries = classpath.files.filter { it.absolutePath != classpathJar.absolutePath }
        classpathJar.parentFile.mkdirs()
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ") { file ->
            val url = file.toURI().toASCIIString()
            if (file.isDirectory && !url.endsWith("/")) "$url/" else url
        }
        JarOutputStream(classpathJar.outputStream(), manifest).use { }
        classpath = files(classpathJar)
        logger.lifecycle("Classpath de teste: ${entries.size} entradas em ${classpathJar.absolutePath}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Offline Portuguese speech (wake word "Aura" and fallback transcript); model in assets/vosk-pt.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    // Voice print (speaker embedding) for the "only my voice" filter; model downloaded on first use.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
