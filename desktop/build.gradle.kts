import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// نسخه بومی ویندوز (Compose Desktop). منطق معامله از ماژول core و صفحه‌ها از پوشه ui-shared
// (همان کد نسخه اندروید) می‌آیند.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets.getByName("main").kotlin.srcDir("../ui-shared/kotlin")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:${libs.versions.composeMultiplatform.get()}")
    implementation(libs.kotlinx.coroutines.swing)
}

val appVersion = "1.4.0"

compose.desktop {
    application {
        mainClass = "com.saeidkazemi.trader.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx512m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MoameleYar"
            packageVersion = appVersion
            description = "Moameleyar - automated trading assistant (Tehran Stock Exchange, FX, Gold, Crypto)"
            vendor = "saeidkazemi"
            copyright = "© 2026 saeidkazemi"
            // برای اطمینان از کارکرد HTTPS و همه قابلیت‌ها، کل ماژول‌های جاوا همراه برنامه بسته‌بندی می‌شوند.
            includeAllModules = true

            windows {
                menuGroup = "MoameleYar"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // شناسه ثابت برای اینکه نسخه‌های بعدی روی نسخه قبلی نصب (به‌روزرسانی) شوند.
                upgradeUuid = "8f5b8a2e-3c1d-4b7a-9f64-2d9e1c7a5b31"
                iconFile.set(project.file("icons/app.ico"))
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
