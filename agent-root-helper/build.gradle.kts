import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

plugins {
    `java-library`
    kotlin("jvm")
}

val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/AppData/Local/Android/Sdk"
val androidJar = File(sdk, "platforms/android-35/android.jar")
val buildTools = File(sdk, "build-tools").listFiles()
    ?.filter { it.isDirectory }?.maxByOrNull { it.name }
    ?: error("Android build-tools not found")
val d8Jar = File(buildTools, "lib/d8.jar")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    compileOnly(files(androidJar))
    implementation(kotlin("stdlib"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("dexJar") {
    group = "build"
    dependsOn("jar")
    val outputDirectory = layout.buildDirectory.dir("helper-dex")
    val outputFile = layout.buildDirectory.file("dsh-root-helper.jar")
    inputs.files(sourceSets.main.get().allJava)
    inputs.files(configurations.runtimeClasspath)
    inputs.file(androidJar)
    inputs.file(d8Jar)
    outputs.file(outputFile)
    mainClass.set("com.android.tools.r8.D8")
    classpath = files(d8Jar)
    val classes = tasks.named("jar")
    doFirst {
        if (!androidJar.isFile) error("android.jar not found: $androidJar")
        if (!d8Jar.isFile) error("d8.jar not found: $d8Jar")
        outputDirectory.get().asFile.deleteRecursively()
        outputDirectory.get().asFile.mkdirs()
        val inputs = mutableListOf(classes.get().outputs.files.singleFile.absolutePath)
        configurations.runtimeClasspath.get().files.filter { it.name.endsWith(".jar") }.forEach { inputs.add(it.absolutePath) }
        args("--lib", androidJar.absolutePath, "--min-api", "29", "--output",
            outputDirectory.get().asFile.absolutePath, *inputs.toTypedArray())
    }
    doLast {
        val dex = outputDirectory.get().file("classes.dex").asFile
        if (!dex.isFile || dex.length() == 0L) error("D8 did not produce classes.dex")
        outputFile.get().asFile.parentFile.mkdirs()
        JarOutputStream(outputFile.get().asFile.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("classes.dex"))
            jar.write(dex.readBytes())
            jar.closeEntry()
        }
        println("root helper: ${outputFile.get().asFile}")
    }
}
