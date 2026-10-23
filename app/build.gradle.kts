import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Backend keys. Preferred: two lines in <repo>/local.properties (gitignored):
//   parse.appId=...
//   parse.clientKey=...
// They become BuildConfig.PARSE_APP_ID / PARSE_CLIENT_KEY. If they are empty, the app falls back at runtime to
// app/src/main/assets/backend.properties (copy backend.properties.example; also gitignored). Never the master key.
val localProps = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
fun localProp(key: String): String = (localProps.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')) ?: "").trim()

android {
    namespace = "com.back4app.expensetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.back4app.expensetracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PARSE_APP_ID", "\"${localProp("parse.appId")}\"")
        buildConfigField("String", "PARSE_CLIENT_KEY", "\"${localProp("parse.clientKey")}\"")
        // Optional parse.serverUrl in local.properties (e.g. http://10.0.2.2:1339/ for scripts/dev-server.mjs from the emulator).
        buildConfigField("String", "PARSE_SERVER_URL", "\"${localProp("parse.serverUrl").ifEmpty { "https://parseapi.back4app.com/" }}\"")
    }

    signingConfigs {
        // Optional release keystore, configured through local.properties (release.storeFile, release.storePassword,
        // release.keyAlias, release.keyPassword). When absent, release builds fall back to the debug keystore so that
        // `./gradlew assembleRelease` still produces an installable APK (not suitable for Play distribution).
        val storeFile = localProp("release.storeFile")
        if (storeFile.isNotEmpty()) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = localProp("release.storePassword")
                keyAlias = localProp("release.keyAlias")
                keyPassword = localProp("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.parse)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
