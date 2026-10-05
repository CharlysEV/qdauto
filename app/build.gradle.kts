plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.qdauto.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.qdauto.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Los tests de la app solo cubren lógica pura (sin Android); los stubs devuelven valores por defecto.
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}
