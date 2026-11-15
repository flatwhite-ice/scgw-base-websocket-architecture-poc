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
    "modules:auth-token",
    "modules:ws-metrics",
    "modules:auth-api",
    "modules:ws-backend-core",
    "modules:ws-backend-service-default",
    "modules:ws-backend-service-chat",
    "modules:ws-gateway",
    "modules:ws-console",
)
