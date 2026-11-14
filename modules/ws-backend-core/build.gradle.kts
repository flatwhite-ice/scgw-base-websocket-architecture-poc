// 여러 WebSocket 서비스(ws-backend, ws-chat)가 함께 쓰는 런타임 라이브러리(D17).
// 실행 가능한 앱이 아니므로 bootJar는 만들지 않는다. 빈은 AutoConfiguration으로 등록된다.
plugins {
    id("wspoc.spring-library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":modules:common-auth"))
    api(libs.spring.boot.starter.webflux)
    api(libs.kotlinx.coroutines.reactor)
    api(libs.jackson.module.kotlin)

    testImplementation(testFixtures(project(":modules:common-auth")))

    // 서비스 모듈의 테스트와 ws-gateway의 E2E 테스트가 함께 쓴다.
    testFixturesApi(project(":modules:common-auth"))
    testFixturesApi(libs.spring.boot.starter.webflux)
    testFixturesApi(libs.kotlinx.coroutines.reactor)
    testFixturesApi(libs.jackson.module.kotlin)
}
