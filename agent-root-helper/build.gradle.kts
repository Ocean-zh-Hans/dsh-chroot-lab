import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

plugins {
    `java-library`
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

dependencies {
    compileOnly(files(androidJar))
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
        args("--lib", androidJar.absolutePath, "--min-api", "29", "--output",
            outputDirectory.get().asFile.absolutePath, classes.get().outputs.files.singleFile.absolutePath)
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
