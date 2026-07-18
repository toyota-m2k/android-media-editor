import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.devtool.ksp)         // for room compiler
}

configure<ApplicationExtension> {
    namespace = "io.github.toyota32k.media.editor"
    compileSdk {
        version = release(37)
        compileSdkMinor = 1
    }

    signingConfigs {
        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { properties.load(it) }
        }

        val keyStorePath: String = properties.getProperty("key_store_path") ?: ""
        val password: String = properties.getProperty("key_password") ?: ""

        create("release") {
            storeFile = file(keyStorePath)
            storePassword = password
            keyAlias = "key0"
            keyPassword = password
        }
    }

    defaultConfig {
        applicationId = "io.github.toyota32k.media.editor"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.slidingpanelayout)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.documentfile)
    ksp(libs.androidx.room.compiler)

    implementation(libs.android.logger)
    implementation(libs.android.utilities)
    implementation(libs.android.viewex)
    implementation(libs.android.binding)
    implementation(libs.android.dialog)
    implementation(libs.android.media.player)
    implementation(libs.android.media.processor)

    implementation(project(":libMediaEditor"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

