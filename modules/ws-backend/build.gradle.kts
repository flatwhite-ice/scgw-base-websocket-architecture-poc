plugins {
    id("wspoc.spring-app-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":modules:common-auth"))

    // 이 모듈의 테스트와 ws-gateway의 E2E 테스트가 함께 쓴다.
    testFixturesApi(project(":modules:common-auth"))
    testFixturesApi(libs.spring.boot.starter.webflux)
    testFixturesApi(libs.kotlinx.coroutines.reactor)
    testFixturesApi(libs.jackson.module.kotlin)
}
