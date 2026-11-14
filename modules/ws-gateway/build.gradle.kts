plugins {
    id("wspoc.spring-app-conventions")
}

dependencies {
    implementation(project(":modules:common-auth"))
    implementation(libs.spring.cloud.starter.gateway.server.webflux)
    implementation(libs.spring.cloud.starter.loadbalancer)

    // E2E tests boot a real ws-backend (relay mode) in the same JVM behind the gateway.
    testImplementation(project(":modules:ws-backend"))
    testImplementation(testFixtures(project(":modules:ws-backend")))
}
