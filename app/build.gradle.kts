plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Release-Signatur (Ablauf: docs/RELEASE.md). Die Schluesseldatei liegt ausserhalb des Repos, das Passwort im
 * macOS-Schluesselbund — nirgends als Klartext. Gelesen wird nur, wenn ein Release-Task angefordert ist; Debug-Builds
 * fassen den Schluesselbund nie an. Pfad, Eintrag und Schluesselbund-Datei lassen sich per -P ueberschreiben.
 */
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val releaseKeystore = file(
    (findProperty("minerKeystore") as String?) ?: "${System.getProperty("user.home")}/.android-keys/core-mobile-miner-release.p12",
)
val releaseKeyAlias = (findProperty("minerKeyAlias") as String?) ?: "tmmac-miner"

fun keychainPassword(): String {
    val service = (findProperty("minerKeychainService") as String?) ?: "core-mobile-miner-release"
    val cmd = mutableListOf("security", "find-generic-password", "-a", releaseKeyAlias, "-s", service, "-w")
    (findProperty("minerKeychainFile") as String?)?.let { cmd += it }  // nur fuer Tests mit eigenem Schluesselbund
    val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val password = proc.inputStream.bufferedReader().readText().trimEnd('\n')
    if (proc.waitFor() != 0 || password.isEmpty()) {
        throw GradleException(
            "Release-Passwort nicht im Schluesselbund (Dienst \"$service\", Konto \"$releaseKeyAlias\"). " +
                "Anlegen: security add-generic-password -a $releaseKeyAlias -s $service -w — siehe docs/RELEASE.md",
        )
    }
    return password
}

android {
    namespace = "com.tellmemoreaboutcore.miner"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.tellmemoreaboutcore.miner"
        minSdk = 28        // Android 9; aeltere Geraete haben selten genug RAM
        targetSdk = 36
        // versionCode bei JEDER verteilten APK +1 (auch Testfassungen), sonst erkennen Android und Obtainium kein Update
        versionCode = 7
        versionName = "1.0.0"
        ndk {
            // Nur arm64: echte Handys und ARM64-Emulatoren. x86_64-Handys gibt es praktisch
            // nicht; 32-Bit-ARM hat keinen RandomY-JIT → zu langsam.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    if (releaseRequested) {
        if (!releaseKeystore.isFile) {
            throw GradleException("Release-Schluessel fehlt: $releaseKeystore — Anlage siehe docs/RELEASE.md")
        }
        val password = keychainPassword()
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storeType = "PKCS12"
            storePassword = password
            keyAlias = releaseKeyAlias
            keyPassword = password  // PKCS12 kennt nur ein Passwort fuer Datei und Schluessel
            // v1 braucht erst Android < 7 (minSdk 28). v3 haelt den Weg fuer eine spaetere Schluessel-Rotation offen,
            // z. B. bei einer Uebergabe an Core; ohne v3 ginge das nur mit neuer App-ID
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            if (releaseRequested) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Lizenztexte fuer "Über diese App" (assets/licenses/…), eine Quelle fuer Repo und App;
    // die eigene Lizenz (LICENSE, NOTICE.md, TRADEMARKS.md) kopiert appLicenseAssets beim Bauen dazu
    sourceSets["main"].assets.srcDir("../third_party")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)

    // buildConfig: BuildConfig.DEBUG schaltet das System-Log im Release ab
    buildFeatures { compose = true; buildConfig = true }

    // Nur die Sprachen, die die App spricht (DE/EN/JA/NL/RO); sonst schleppen AndroidX-Bibliotheken ~90 mit.
    // generateLocaleConfig: Android 13+ zeigt die App unter "App-Sprachen" in den Systemeinstellungen; die Liste
    // kommt aus den values-*-Ordnern, values/ ist Englisch (res/resources.properties).
    androidResources {
        localeFilters += listOf("de", "en", "ja", "nl", "ro")
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val appLicenseAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) { rename { "GPL-3.0.txt" } }
    from(rootProject.file("NOTICE.md")) { rename { "NOTICE.txt" } }
    // Markenregeln gehoeren zu den Zusaetzen nach GPL §7 (NOTICE verweist darauf), darum mit in die APK
    from(rootProject.file("TRADEMARKS.md")) { rename { "TRADEMARKS.txt" } }
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
}
tasks.named("preBuild") { dependsOn(appLicenseAssets) }

dependencies {
    implementation(project(":miner-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    // QR-Scanner ohne Google-Dienste (Apache-2.0); Kamera-Recht fragt die Scan-Activity erst beim Scannen
    implementation(libs.zxing.android.embedded)
}
