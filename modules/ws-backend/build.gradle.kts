plugins {
    id("wspoc.spring-app-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":modules:common-auth"))

    // Shared by this module's tests and ws-gateway's E2E tests.
    testFixturesApi(project(":modules:common-auth"))
    testFixturesApi(libs.spring.boot.starter.webflux)
    testFixturesApi(libs.kotlinx.coroutines.reactor)
    testFixturesApi(libs.jackson.module.kotlin)
}
