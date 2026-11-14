// Shared conventions for every module: Kotlin/JVM toolchain, ktlint, JUnit 5 + BlockHound.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    // Spring Boot BOM as a plain Gradle platform: aligns versions without adding Spring to the runtime classpath.
    "implementation"(platform(lib("spring-boot-bom")))
    "testImplementation"(platform(lib("spring-boot-bom")))
    "testImplementation"(lib("kotlin-test-junit5"))
    "testImplementation"(lib("kotlinx-coroutines-test"))
    "testImplementation"(lib("blockhound"))
    "testImplementation"(lib("blockhound-junit-platform"))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // BlockHound attaches its agent dynamically and needs AllowRedefinitionToAddDeleteMethods on JDK 13+ (still available on 25, deprecated).
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods", "-XX:+EnableDynamicAgentLoading")
}
