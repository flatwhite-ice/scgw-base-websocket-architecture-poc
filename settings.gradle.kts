pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "websocket-poc"

include(
    "modules:common-auth",
    "modules:auth-api",
    "modules:ws-backend",
    "modules:ws-gateway",
)
