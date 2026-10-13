import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.compose") version "1.12.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

group = "com.cloudstreampc"
version = "3.0.8"

// Commit of recloudstream/cloudstream whose `library` (the extension API) we embed.
val cloudstreamLibrary = "4846fbacd2"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
        )
    }
}

dependencies {
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material", module = "material")
    }
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // CloudStream extension API (MainAPI, extractors, NiceHttp `app`, ...)
    implementation("com.github.recloudstream.cloudstream:library-jvm:$cloudstreamLibrary")
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jsoup:jsoup:1.22.1")
    // mDNS discovery of Chromecasts (the cast protocol itself is in cast/Chromecast.kt).
    implementation("org.jmdns:jmdns:3.6.4")
    // QR code of the phone remote (30 Ko).
    implementation("io.nayuki:qrcodegen:1.8.0")
    // Gamepads (SDL, natives included for Windows, Linux and macOS).
    implementation("com.badlogicgames.jamepad:jamepad:2.30.0.0")

    // Extensions (.cs3) ship Android DEX bytecode: converted to JVM classes at install time.
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.38")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("cloudstreampc.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    listOf("test.network", "test.search", "test.repo", "test.tasteOut", "test.trailerVideo", "test.mpv", "test.browser", "test.soak", "test.soak.sources", "test.soak.channels", "test.soak.buffer", "test.soak.iptv", "test.soak.iptv.ids", "test.fstv").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "com.cloudstreampc.MainKt"
        // Release builds drop the unused parts of Compose (see proguard-rules.pro).
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate.set(false)
            optimize.set(false)
            joinOutputJars.set(false)
        }
        // A crash of Java itself leaves its report next to the app (picked up at the next start, see CrashReport).
        jvmArgs += listOf("-Xmx1g", "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-XX:ErrorFile=\$APPDIR/../hs_err_pid%p.log")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "CloudStreamPC"
            packageVersion = project.version.toString()
            description = "CloudStream pour ordinateur"
            vendor = "CloudStreamPC"
            // From `./gradlew suggestRuntimeModules`, plus TLS (crypto.ec), legacy page charsets, zip and the
            // JavaScript engine some extensions call through javax.script. jdk.httpserver runs the local
            // servers: live TV relay, cast proxy and the sign-in page of AniList / MyAnimeList.
            modules(
                "java.instrument", "java.management", "java.net.http", "java.sql", "java.naming", "java.scripting",
                "jdk.dynalink", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "jdk.zipfs", "jdk.httpserver",
            )
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))

            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                menuGroup = "CloudStreamPC"
                upgradeUuid = "6f1b3f0e-2b8e-4c39-9d8e-6c1c2f5a7e41"
                iconFile.set(project.file("icons/icon.ico"))
            }
            linux {
                packageName = "cloudstreampc"
                debMaintainer = "cloudstreampc@users.noreply.github.com"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                shortcut = true
                iconFile.set(project.file("icons/icon.png"))
            }
            macOS {
                bundleID = "io.github.nikola17.cloudstreampc"
                dockName = "CloudStreamPC"
                appCategory = "public.app-category.entertainment"
                // Made from icons/icon.png by the macOS build (sips + iconutil).
                project.file("icons/icon.icns").takeIf { it.isFile }?.let { iconFile.set(it) }
            }
        }
    }
}
