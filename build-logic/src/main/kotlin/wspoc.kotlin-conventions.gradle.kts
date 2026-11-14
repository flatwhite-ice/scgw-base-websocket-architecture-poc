// 모든 모듈 공통 규칙: Kotlin/JVM toolchain, ktlint, JUnit 5 + BlockHound.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    // Spring Boot BOM을 일반 Gradle platform으로 가져온다: 런타임 클래스패스에 Spring을 넣지 않고 버전만 맞춘다.
    "implementation"(platform(lib("spring-boot-bom")))
    "testImplementation"(platform(lib("spring-boot-bom")))
    "testImplementation"(lib("kotlin-test-junit5"))
    "testImplementation"(lib("kotlinx-coroutines-test"))
    "testImplementation"(lib("blockhound"))
    "testImplementation"(lib("blockhound-junit-platform"))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
}

plugins.withId("java-test-fixtures") {
    dependencies { "testFixturesApi"(platform(lib("spring-boot-bom"))) }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // BlockHound는 에이전트를 동적으로 붙이며 JDK 13+에서 AllowRedefinitionToAddDeleteMethods가 필요하다(25에서도 deprecated 상태로 존재).
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods", "-XX:+EnableDynamicAgentLoading")
}
