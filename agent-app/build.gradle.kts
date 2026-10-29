plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "dev.dsh.agent"
    compileSdk = 35

    sourceSets {
        getByName("main") {
            manifest.srcFile("${projectDir}/AndroidManifest.xml")
            java.srcDirs("${projectDir}/src")
            res.srcDirs("${projectDir}/res")
        }
        getByName("test") {
            java.srcDirs("${projectDir}/test-src")
        }
    }

    defaultConfig {
        applicationId = "dev.dsh.agent"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

}

dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle.lockfile"))
    lockAllConfigurations()
}

tasks.named("preBuild") {
    dependsOn(":agent-root-helper:dexJar")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":agent-core"))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
