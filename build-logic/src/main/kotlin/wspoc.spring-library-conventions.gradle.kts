// Spring 빈을 제공하지만 실행 가능한 앱은 아닌 라이브러리 모듈 공통 규칙(예: ws-backend-core).
// Boot 플러그인(bootJar)은 쓰지 않고, 버전은 Boot BOM으로 맞춘다.
plugins {
    id("wspoc.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "api"(platform(lib("spring-boot-bom")))
    "implementation"(lib("kotlin-reflect"))
    "testImplementation"(lib("spring-boot-starter-webflux-test"))
}
