import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ماژول مشترک (Kotlin/JVM خالص): مدل‌ها، منابع داده، اخبار، تحلیل، مدیریت ریسک و موتور معاملات.
// هم اپ اندروید و هم نسخه ویندوز از همین ماژول استفاده می‌کنند.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.retrofit)
    api(libs.converter.gson)
    api(libs.okhttp)
    api(libs.gson)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
