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
    "modules:ws-backend-core",
    "modules:ws-backend",
    "modules:ws-chat",
    "modules:ws-gateway",
    "modules:ws-console",
)
