import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.qdauto.carsim.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
