plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Número da versão: no GitHub Actions vem do número da execução (-PversionCode=N).
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()

android {
    namespace = "com.bertonibr.pdfstudio"
    compileSdk = 35

    // Chave fixa: todas as versões têm a mesma assinatura, então o Android aceita
    // instalar uma atualização por cima da anterior (sem desinstalar).
    signingConfigs {
        create("app") {
            storeFile = file("pdfstudio.keystore")
            storePassword = "pdfstudio"
            keyAlias = "pdfstudio"
            keyPassword = "pdfstudio"
        }
    }

    defaultConfig {
        applicationId = "com.bertonibr.pdfstudio"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 2
        versionName = "1.1." + (ciVersionCode ?: 0)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
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
    testOptions {
        animationsDisabled = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Edição de PDF (porta do Apache PDFBox para Android)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // API do Claude
    implementation("com.anthropic:anthropic-java:3.2.0")

    // Testes no emulador/aparelho (./gradlew connectedDebugAndroidTest)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
