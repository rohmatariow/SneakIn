plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "com.sneakin"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Montoya API is provided by Burp at runtime — compile against it, do NOT bundle it.
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.4")

    // Bundled into the fat jar (Burp does not provide these on the classpath).
    implementation("com.google.code.gson:gson:2.11.0")

    // Unit tests for the pure parsing/oracle logic (not bundled).
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    // Target the JVM that Burp 2026.x ships with. A 21 toolchain is auto-provisioned by foojay,
    // so the compiler does not run on the host's bleeding-edge JDK.
    jvmToolchain(21)
}

tasks.shadowJar {
    // Produce a single fat jar with the Kotlin runtime + Gson bundled.
    archiveBaseName.set("sneakin")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}

// `gradle build` should always produce the loadable fat jar.
tasks.build {
    dependsOn(tasks.shadowJar)
}
