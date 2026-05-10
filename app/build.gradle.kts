import com.android.build.api.dsl.Packaging
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val appPropsFile = rootProject.file("application.properties")
val appProps = Properties()
if (appPropsFile.exists()) {
    appProps.load(FileInputStream(appPropsFile))
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

val releaseStoreFilePath = keystoreProps.getProperty("storeFile") ?: System.getenv("ANDROID_KEYSTORE_FILE")
val releaseStorePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning =
    !releaseStoreFilePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.graphenelab.photosync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.graphenelab.photosync"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders += mapOf("appAuthRedirectScheme" to "com.graphenelab.photosync")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"

            buildConfigField("boolean", "IS_DEBUG", "true")
            buildConfigField("String", "STRIPE_PUBLIC_KEY", "\"${appProps.getProperty("STRIPE_PUBLIC_KEY_DEBUG")}\"")
            buildConfigField("String", "BASE_URL", "\"${appProps.getProperty("BASE_URL_DEBUG")}\"")
        }
        release {
            isMinifyEnabled = false
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Release signing is not configured. Add keystore.properties or ANDROID_KEYSTORE_* env vars."
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "IS_DEBUG", "false")
            buildConfigField("String", "STRIPE_PUBLIC_KEY", "\"${appProps.getProperty("STRIPE_PUBLIC_KEY_RELEASE")}\"")
            buildConfigField("String", "BASE_URL", "\"${appProps.getProperty("BASE_URL_RELEASE")}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
//    fun Packaging.() {
//        resources {
//            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
//        }
//    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    // AndroidX & Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.navigation)

    // Jetpack Data & Work
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Hilt (Dependency Injection)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.common)


    // Serialization & Cryptography
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    // QR Code Scanner
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    // Unit Testing
    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(kotlin("test"))

    // Android Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // OAuth
    implementation(libs.appauth)
    implementation(libs.jwtdecode)

    // Secure Storage
    implementation(libs.androidx.security.crypto)

    // Stripe for Android
//    implementation(libs.stripe.android)

    // Retrofit for networking
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp.core)
    implementation(libs.logging.interceptor)



    // Client-side Encryption
    implementation(libs.kotlin.bip39)

    // communicationLib
    implementation(project(":communicationLib"))
}
