plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// CLI fuer den End-to-End-Test vom Server gegen den echten Pool
application { mainClass.set("com.tellmemoreaboutcore.miner.core.CliKt") }

tasks.test {
    useJUnitPlatform()
    // Fuer HashSelfTestTest: Host-Build der JNI-Lib (fehlt er, wird der Test uebersprungen)
    systemProperty("java.library.path", rootProject.layout.projectDirectory.dir("build/host").asFile.absolutePath)
    // ConsentTest liest Texte der App und NOTICE.md: als Eingaben melden, sonst gilt der Test nach einer
    // Textaenderung als aktuell und wird uebersprungen (der Hash-Waechter liefe ins Leere)
    inputs.files(
        rootProject.file("NOTICE.md"),
        rootProject.file("app/src/main/kotlin/com/tellmemoreaboutcore/miner/About.kt"),
        rootProject.fileTree("app/src/main/res") { include("values*/strings.xml") },
    ).withPropertyName("consentTexts")
}
