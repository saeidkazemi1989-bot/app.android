plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.saeidkazemi.trader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saeidkazemi.trader"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    // کلید امضای ثابت: همه نسخه‌ها با یک کلید امضا می‌شوند تا نسخه جدید روی نسخه قبلی نصب (به‌روزرسانی) شود.
    // (قبلاً هر بار ساخت در CI یک کلید دیباگ تصادفی تازه می‌ساخت و گوشی به‌روزرسانی را «تداخل بسته» رد می‌کرد.)
    signingConfigs {
        create("shared") {
            storeFile = file("signing/moameleyar.p12")
            storePassword = System.getenv("MOAMELEYAR_STORE_PASSWORD") ?: "MoameleYar-Sign-2026"
            keyAlias = "moameleyar"
            keyPassword = System.getenv("MOAMELEYAR_STORE_PASSWORD") ?: "MoameleYar-Sign-2026"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // صفحه‌های رابط کاربری بین اندروید و ویندوز مشترک‌اند (پوشه ui-shared).
    sourceSets {
        getByName("main") {
            java.srcDir("../ui-shared/kotlin")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/previous-compilation-data.bin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
