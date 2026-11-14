// Conventions for runnable Spring Boot (WebFlux + Reactor Netty) applications. MVC is intentionally not used.
plugins {
    id("wspoc.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "implementation"(platform(lib("spring-cloud-bom")))
    "implementation"(lib("spring-boot-starter-webflux"))
    "implementation"(lib("kotlin-reflect"))
    "implementation"(lib("kotlinx-coroutines-reactor"))
    "implementation"(lib("reactor-kotlin-extensions"))
    "implementation"(lib("jackson-module-kotlin"))
    "testImplementation"(lib("spring-boot-starter-webflux-test"))
    "testImplementation"(lib("reactor-test"))
}
